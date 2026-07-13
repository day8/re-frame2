(ns re-frame.epoch.listeners
  "Epoch listener fan-out and frame teardown.

  Two responsibilities live here:

    1. `notify-listeners!` — fan a built record out to every registered
       `register-epoch-listener!` callback. Each invocation is wrapped in a
       try/catch so one broken listener cannot break the runtime or
       block other listeners; a
       failing cb emits `:rf.epoch.cb/listener-exception` so devtools
       can surface the broken consumer.

    2. `on-frame-destroyed!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/on-frame-destroyed` slot. Coordinates the four-step
       destroy contract:

         (a) mid-drain destroy detection → commit a `:halted-destroy`
             partial record (carrying the real pre-cascade `:db-before`
              and destroy-time snapshots) to listeners, not the ring.
         (b) emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb
             whose observed-frames set contained `frame-id`.
         (c) drop the per-frame ring buffer.
         (d) drop the in-flight capture buffer.

  Listener registry and observation bookkeeping live in
  `re-frame.epoch.state`."
  (:require [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

(defn- deliver-listener-snapshot!
  "Deliver `record` to an already-snapshotted listener generation.

  Observation stamping belongs to the caller: ordinary fan-out stamps before
  delivery; terminal destroy fan-out snapshots the silencing set and removes
  the dying incarnation's observation state atomically before delivery."
  [record listener-snapshot]
  (let [frame-id (:frame record)]
    (doseq [[id {:keys [callback]}] listener-snapshot]
      (try
        (callback record)
        (catch #?(:clj Throwable :cljs :default) ex
          (trace/emit-error! :rf.epoch.cb/listener-exception
                             {:frame       frame-id
                              :cb-id       id
                              :rf.epoch/id (:epoch-id record)
                              :message     #?(:clj  (.getMessage ^Throwable ex)
                                              :cljs (.-message ex))
                              :recovery    :no-recovery}))))))

(defn notify-listeners!
  "Fan `record` out to every registered `:rf/epoch-record` listener.

  Each cb is invoked once per record, wrapped in failure isolation:
  Listener failures do not stop the loop. A failing callback emits a structured
  `:rf.epoch.cb/listener-exception` trace so devtools can surface
  the broken listener (silently swallowing the throw left tool
  authors with no signal that their callback failed).

  Op-type `:rf.epoch.cb` matches the sibling
  `:rf.epoch.cb/silenced-on-frame-destroy` event (per Spec 009
  §Op-type vocabulary catalogue and `epoch.cljc` row).
  `:recovery :no-recovery` mirrors the `:rf.http/aborted` trace
  shape — the listener's invocation is over; the next cascade
  re-invokes the same fn afresh, no automatic remediation happens
  between now and then."
  [record]
  (let [frame-id          (:frame record)
        listener-snapshot (state/listeners-snapshot)]
    (doseq [[id {:keys [generation]}] listener-snapshot]
      ;; Stamp the observation against the EXACT generation being invoked, so a
      ;; concurrent same-id replacement can neither erase this observation nor
      ;; inherit it (rf2-j538f7.5).
      (state/record-observation! id generation frame-id))
    (deliver-listener-snapshot! record listener-snapshot)))

;; ---- post-settle render back-fill -----------------------------------------

(defn record-render!
  "Attribute a post-settle render emit to the event that caused it.

  A `:view/render` / `:rf.view/rendered` trace fires at React commit
  time — AFTER the causing cascade settled — so it cannot ride the
  in-flight cascade buffer. Back-filling it into
  the frame's most-recently-settled epoch; that is right for a genuine
  reactive re-render but wrong for a MOUNT render whose commit lands late
  (React batches a freshly-mounted component's render onto a later tick,
  so it can commit AFTER the first user cascade settles). Attributed to
  last-settled, a mount render leaks spuriously into the first post-mount
  cascade's `:renders`.

  `state/resolve-render-epoch` picks the right anchor: the newest epoch
  where the rendering view's OWN inputs changed (a real re-render rides
  its causing cascade), else the view's
  MOUNT epoch (a mount / mount-burst tail anchors where the instance
  first rendered), else the most-recently-settled epoch (a brand-new
  instance whose first render commits post-settle — the settling cascade
  becomes its mount). The chosen epoch is recorded as the render-key's
  mount epoch on first sighting so subsequent late renders resolve
  against it.

  A render that resolves back to its mount epoch where it ALREADY landed
  is de-duped (no second `:renders` row, no re-notify) — that is the
  mount-burst tail being absorbed rather than re-filed.

  Re-fans the corrected record out to epoch listeners so snapshot
  consumers (Xray Views / Reactive panel, which cache `epoch-history`
  at settle time) re-sync to the corrected `:renders`.

  No-op when the frame has no settled epoch yet (a render before the
  first cascade) or when the target epoch has been evicted from the ring
  — `back-fill-render!` returns nil and we skip the re-notify."
  [frame-id event]
  (when interop/debug-enabled?
    (when-let [default-epoch (state/last-settled-epoch-id frame-id)]
      (let [render-key (-> event :tags :rf.view/render-key)
            target     (state/resolve-render-epoch frame-id render-key
                                                    default-epoch)]
        ;; Record the mount anchor on first sighting; never overwrites.
        (state/record-mount-epoch! frame-id render-key target)
        ;; De-dup a mount-burst tail ONLY when it was REDIRECTED away from
        ;; the settling cascade (`target` ≠ `default-epoch`) back to a
        ;; mount epoch where the instance already rendered. A genuine
        ;; re-render resolves to the settling cascade (`target` =
        ;; `default-epoch`) and is never de-duped — so the paired
        ;; `:view/render` + `:rf.view/rendered` of one real render both
        ;; ride their cascade (only the late mount-burst tail is absorbed).
        (when-not (and render-key
                       (not= target default-epoch)
                       (state/render-key-already-in-epoch?
                         frame-id target render-key))
          ;; Back-fill stores the raw event and row; egress projection remains
          ;; the only redaction boundary.
          (when-let [updated (state/back-fill-render! frame-id target event
                                                      (capture/render-row event))]
            ;; Re-fan the corrected record so snapshot consumers re-read
            ;; the ring. The fan-out is failure-isolated per listener
            ;; (same contract as the settle-time fan-out); a render-driven
            ;; Xray pump (`:rf.xray/epoch-recorded`) is
            ;; `:rf.trace/no-emit?` so it commits no new epoch and cannot
            ;; loop back into this path.
            (notify-listeners! updated)))))))

;; ---- post-settle sub-run back-fill ----------------------------------------

(defn record-sub-run!
  "Attribute a post-settle sub-run to the event that caused it. A `:sub/run` /
  `:rf.sub/skip` trace fires when a reaction recomputes, and reactions
  recompute lazily at React deref time — AFTER the causing cascade settled
  — so it cannot ride the in-flight cascade buffer (there is none). This
  back-fills the sub-run into the frame's most-recently-settled epoch
  record and re-fans the updated record out to epoch listeners so snapshot
  consumers (Xray's per-cascade Views subs table, which caches
  `epoch-history` at settle time) re-sync to the corrected `:sub-runs` +
  `:value-changed?` attribution.

  No-op when the frame has no settled epoch yet (a sub-run before the
  first cascade) or when the target epoch has been evicted from the ring
  — `back-fill-sub-run!` returns nil and we skip the re-notify."
  [frame-id event]
  (when interop/debug-enabled?
    (when-let [epoch-id (state/last-settled-epoch-id frame-id)]
      ;; Store raw; projection remains the egress boundary.
      (when-let [updated (state/back-fill-sub-run! frame-id epoch-id event
                                                   (capture/sub-run-row event))]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. Same failure-isolated fan-out + no-loop contract as the
        ;; render back-fill above.
        (notify-listeners! updated)))))

;; ---- post-settle view-unmount back-fill -----------------------------------

(defn record-unmount!
  "Attribute a post-settle view-unmount emit to the event that caused the
  teardown. A
  `:rf.view/unmounted` trace fires at React `componentWillUnmount` /
  `useEffect`-cleanup time — AFTER the cascade that removed the view settled
  — so it cannot ride the in-flight cascade buffer (there is none).

  With no in-flight cascade and no `:dispatch-id`, the unmount would
  otherwise fall through `capture-event!`'s orphan-drop branch and leave
  no signal in the epoch record — so Xray's VIEWS-step
  `unmounted-views-rows`, which reads `:rf.view/unmounted` off
  `:trace-events`, would have nothing to surface.

  This back-fills the unmount into the frame's most-recently-settled epoch
  (the cascade that drove the teardown). Unlike `record-render!` there is NO
  render-key mount-resolution and NO de-dup: an unmount is a one-shot
  per-instance teardown that belongs to the settling cascade, exactly like a
  post-settle sub-run. The unmount carries no structured projection row (it
  is neither a `:renders` nor a `:sub-runs` entry), so `back-fill-event!` is
  invoked with `row` nil — it rides ONLY the `:trace-events` slot. The
  updated record is re-fanned to epoch listeners so snapshot consumers
  (Xray's Views panel, which caches `epoch-history` at settle time) re-sync.

  After back-fill, evict the unmounting
  instance's `mount-attribution` entry (the `:epoch-id` anchor + learned
  `:deps` read-set keyed by this `render-key`). Each mount mints a FRESH
  `instance-token` → a fresh render-key, so without per-instance eviction the
  map accreted one permanent entry per ever-mounted instance, pruned ONLY on
  whole-frame destroy — unbounded per-frame heap over a long churning session
  (the time-travel scenario this surface serves). The eviction runs
  UNCONDITIONALLY of whether the back-fill found a live epoch: the entry is
  dead either way once the instance tears down (a fixture-teardown unmount with
  no settled epoch still strands an entry otherwise). The render-key is read
  from the same `:rf.view/render-key` tag the mount path uses
  (`record-render!`); a late mount-burst tail arriving after the prune
  harmlessly re-mints the entry.

  No-op (for the back-fill leg) when the frame has no settled epoch yet (an
  unmount before the first cascade — e.g. a fixture teardown) or when the
  target epoch has been evicted from the ring — `back-fill-unmount!` returns
  nil and we skip the re-notify."
  [frame-id event]
  (when interop/debug-enabled?
    (when-let [epoch-id (state/last-settled-epoch-id frame-id)]
      ;; Unmount has no structured row, so only retained raw trace is updated.
      (when-let [updated (state/back-fill-unmount! frame-id epoch-id event)]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. Same failure-isolated fan-out + no-loop contract as the
        ;; render / sub-run back-fill above.
        (notify-listeners! updated)))
    ;; Prune the unmounting instance's mount-attribution entry so
    ;; the map stays bounded across instance churn (NOT retained until
    ;; whole-frame destroy). Runs whether or not the back-fill found a live
    ;; epoch — the entry is dead once the instance unmounts.
    (state/drop-render-key-mount-attribution!
      frame-id (-> event :tags :rf.view/render-key))))

(defn on-frame-destroyed!
  "Handle epoch state when a frame is destroyed.

  If a run-start is still buffered, listeners receive a raw
  `:halted-destroy` record containing the pre-run and destroy-time whole-frame
  snapshots plus the destroying event's causal time. The record is not stored:
  destroyed frames have empty history, so listener delivery is the only channel
  for this terminal partial record.

  `owner-token` is the destroyed frame's stable incarnation token. Each
  listener whose CURRENT generation observed the frame receives one
  silencing trace (a stale observation from a since-replaced same-id generation
  is skipped — `state/cbs-observing-frame` scopes the fan to the live
  generation). The observation, history, capture, last-settled, and
  mount-attribution entries are then removed so a same-keyed replacement frame
  starts clean. Owner comparison and cleanup are serialised with fresh
  same-id epoch publication: stale A cleanup no-ops after B has claimed these
  id-keyed stores. Repeated destroys are idempotent."
  [frame-id owner-token fs-before fs-after committed-at]
  (when interop/debug-enabled?
    (when-let [{:keys [record listener-snapshot silenced-cbs]}
               (state/cleanup-frame-owner!
                 frame-id owner-token
                 (fn []
                   ;; Snapshot all terminal evidence and remove every id-keyed
                   ;; side table under the exact-owner serialization. External
                   ;; callbacks run only after the lock is released, so a
                   ;; callback may safely create and publish same-id B.
                   (let [buffered-events  (state/buffer-for frame-id)
                         record           (when (capture/in-flight-cascade? frame-id)
                                            (assembly/build-record
                                              frame-id fs-before fs-after
                                              buffered-events committed-at
                                              :halted-destroy
                                              {:operation :rf.frame/destroyed-mid-drain}))
                         listener-snapshot (if record
                                             (state/listeners-snapshot)
                                             {})
                         ;; A terminal partial record is observed by every
                         ;; listener generation in its delivery snapshot.
                         silenced-cbs     (into (set (state/cbs-observing-frame frame-id))
                                                (keys listener-snapshot))]
                     (state/drop-frame-observation! frame-id)
                     (state/drop-frame-history! frame-id)
                     (state/drop-frame-buffer! frame-id)
                     (state/drop-last-settled-epoch! frame-id)
                     (state/drop-frame-mount-attribution! frame-id)
                     {:record            record
                      :listener-snapshot listener-snapshot
                      :silenced-cbs      silenced-cbs})))]
      (when record
        ;; Use the same detailed/coarse trailer pair as normal commits. These
        ;; terminal emits are explicitly excluded from capture ownership.
        (assembly/emit-snapshotted+outcome! frame-id (:epoch-id record)
                                            (:event-id record) :halted-destroy)
        (deliver-listener-snapshot! record listener-snapshot))
      (doseq [cb-id silenced-cbs]
        (trace/emit! :rf.epoch.cb :rf.epoch.cb/silenced-on-frame-destroy
                     {:frame frame-id :cb-id cb-id})))))
