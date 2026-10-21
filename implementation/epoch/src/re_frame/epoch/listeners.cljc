(ns re-frame.epoch.listeners
  "Epoch listener fan-out and frame teardown.

  Three responsibilities live here:

    1. `notify-listeners!` — fan a built record out to every registered
       `register-epoch-listener!` callback. Each invocation is wrapped in a
       try/catch so one broken listener cannot break the runtime or
       block other listeners; a
       failing cb emits `:rf.epoch.cb/listener-exception` so devtools
       can surface the broken consumer.

    2. `snapshot-terminal-destroy-evidence!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/snapshot-frame-destroyed` slot BEFORE dissoc, binding the
       dying incarnation's `:halted-destroy` record (and the listeners it
       fans to) while it still solely owns its id-keyed stores.

    3. `on-frame-destroyed!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/on-frame-destroyed` slot AFTER dissoc. It delivers the
       pre-dissoc bundle's `:halted-destroy` record (the work a pending
       destroy dropped at its boundary), if any, to listeners rather than the
       ring, and drops the frame's id-keyed stores.

       Publication does not depend on the store drop: the record was bound
       before dissoc.

  The listener registry lives in `re-frame.epoch.state`."
  (:require [re-frame.epoch.assembly :as rf.epoch.assembly]
            [re-frame.epoch.capture :as rf.epoch.capture]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.interop :as rf.interop]
            [re-frame.trace :as rf.trace]))

(defn- deliver-listener-snapshot!
  "Deliver `record` to an already-snapshotted `{cb-id → callback}` map.

  `terminal?` marks the destroy fan-out of a destroyed incarnation's
  `:halted-destroy` record. That fan-out runs after teardown released the
  frame's trace ring and no-emit flag (Spec 002 §Destroy step 8), so a failing
  listener's `:rf.epoch.cb/listener-exception` diagnostic is delivered
  STRUCTURALLY — no ring retention, epoch capture or bare-id policy — because
  an ordinary emit would recreate a ring under an id whose frame is gone (Spec
  009 §Retention boundary). Ordinary
  (non-terminal) fan-out honours the live frame's no-emit policy."
  ([record listener-snapshot]
   (deliver-listener-snapshot! record listener-snapshot (constantly true) true))
  ([record listener-snapshot continue? terminal?]
   (let [frame-id (:frame record)
         emit-listener-exception!
         (fn [callback-id listener-error]
           (rf.trace/emit-error! :rf.epoch.cb/listener-exception
                               {:frame       frame-id
                                :cb-id       callback-id
                                :rf.epoch/id (:epoch-id record)
                                :message     #?(:clj  (.getMessage ^Throwable listener-error)
                                                :cljs (.-message listener-error))
                                :recovery    :no-recovery}))]
     (loop [entries (seq listener-snapshot)]
       (when (and entries (continue?))
          (let [[callback-id callback] (first entries)]
            (try
              (callback record)
              (catch #?(:clj Throwable :cljs :default) listener-error
               ;; A callback that destroyed the exact owner has an inert throw;
               ;; never emit its failure diagnostic against same-id B.
               (when (continue?)
                 (if terminal?
                   ;; A terminal fault arrives after the frame's ring was
                   ;; released, so it must not be retained under the dead id.
                    (rf.trace/call-with-structural-delivery
                      #(emit-listener-exception! callback-id listener-error))
                    (emit-listener-exception! callback-id listener-error)))))
           (recur (next entries))))))))

(defn notify-listeners!
  "Fan `record` out to every registered `:rf/epoch-record` listener.

  Each cb is invoked once per record, wrapped in failure isolation:
  Listener failures do not stop the loop. A failing callback emits a structured
  `:rf.epoch.cb/listener-exception` trace so devtools can surface
  the broken listener (silently swallowing the throw would leave tool
  authors with no signal that their callback failed).

  The trace's op-type is `:rf.epoch.cb`, the epoch-callback family (Spec 009
  §`:op-type` vocabulary).
  `:recovery :no-recovery` mirrors the `:rf.http/aborted` trace
  shape — the listener's invocation is over; the next cascade
  re-invokes the same fn afresh, no automatic remediation happens
  between now and then."
  ([record]
   (notify-listeners! record (constantly true)))
  ([record continue?]
   (let [listener-snapshot (rf.epoch.state/listeners-snapshot)]
     (when (continue?)
       (deliver-listener-snapshot! record listener-snapshot continue? false)))))

;; ---- post-settle render back-fill -----------------------------------------

(defn record-render!
  "Attribute a post-settle render emit to the event that caused it.

  A `:view/render` / `:rf.view/rendered` trace fires at React commit
  time — AFTER the causing cascade settled — so it cannot ride the
  in-flight cascade buffer. Back-filling it into
  the frame's most-recently-settled epoch is right for a genuine
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

  A render attributed through a post-settle sub-run is no more certain than
  that run, so its row carries the run's `:epoch-window`
  (`state/render-epoch-window`).

  Re-fans the corrected record out to epoch listeners so snapshot
  consumers (Xray Views / Reactive panel, which cache `epoch-history`
  at settle time) re-sync to the corrected `:renders`.

  No-op when the frame has no settled epoch yet (a render before the
  first cascade) or when the target epoch has been evicted from the ring
  — `back-fill-render!` returns nil and we skip the re-notify."
  [frame-id event]
  (when rf.interop/debug-enabled?
    (when-let [default-epoch-id (rf.epoch.state/last-settled-epoch-id frame-id)]
      (let [render-key (-> event :tags :rf.view/render-key)
            target-epoch-id (rf.epoch.state/resolve-render-epoch frame-id render-key
                                                         default-epoch-id)]
        ;; Record the mount anchor on first sighting; never overwrites.
        (rf.epoch.state/record-mount-epoch! frame-id render-key target-epoch-id)
        ;; De-dup a mount-burst tail ONLY when it was REDIRECTED away from
        ;; the settling cascade (`target-epoch-id` ≠ `default-epoch-id`) back to a
        ;; mount epoch where the instance already rendered. A genuine
        ;; re-render resolves to the settling cascade (`target-epoch-id` =
        ;; `default-epoch-id`) and is never de-duped — so the paired
        ;; `:view/render` + `:rf.view/rendered` of one real render both
        ;; ride their cascade (only the late mount-burst tail is absorbed).
        (when-not (and render-key
                       (not= target-epoch-id default-epoch-id)
                       (rf.epoch.state/render-key-already-in-epoch?
                         frame-id target-epoch-id render-key))
          ;; Back-fill stores the raw event and row; egress projection remains
          ;; the only redaction boundary.
          (when-let [updated-record
                     (let [render-row   (rf.epoch.capture/render-row event)
                           epoch-window (when render-row
                                          (rf.epoch.state/render-epoch-window
                                            frame-id render-key target-epoch-id))]
                       (rf.epoch.state/back-fill-render!
                         frame-id target-epoch-id event
                         (cond-> render-row
                           epoch-window (assoc :epoch-window epoch-window))))]
            ;; Re-fan the corrected record so snapshot consumers re-read
            ;; the ring. The fan-out is failure-isolated per listener
            ;; (same contract as the settle-time fan-out); a render-driven
            ;; Xray pump (`:rf.xray/epoch-recorded`) is
            ;; `:rf.trace/no-emit?` so it commits no new epoch and cannot
            ;; loop back into this path.
            (notify-listeners! updated-record)))))))

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

  A `:sub-return` / `:sub-override` schema failure from the same recompute
  arrives here too, so it lands in the same epoch as its run. Like a
  `:rf.sub/skip` it projects no `:sub-runs` row, so it rides only
  `:trace-events`, where Xray attaches it to the SUBSCRIPTIONS row.

  The last-settled epoch is where the run is FILED, not necessarily what
  caused it: every epoch settled since the sub's previous run may have
  changed its inputs. So a `:rf.sub/run` is stamped `:rf.sub/epoch-window`
  (`state/sub-run-epoch-window`) before it is stored, and `sub-run-row`
  threads the same window onto its row.

  No-op when the frame has no settled epoch yet (a sub-run before the
  first cascade) or when the target epoch has been evicted from the ring
  — `back-fill-sub-run!` returns nil and we skip the re-notify."
  [frame-id event]
  (when rf.interop/debug-enabled?
    (when-let [epoch-id (rf.epoch.state/last-settled-epoch-id frame-id)]
      ;; Store raw; projection remains the egress boundary.
      (when-let [updated-record
                 (let [event (cond-> event
                               (= :rf.sub/run (:operation event))
                               (assoc-in [:tags :rf.sub/epoch-window]
                                         (rf.epoch.state/sub-run-epoch-window
                                           frame-id epoch-id
                                           (-> event :tags :rf.sub/query-v))))]
                   (rf.epoch.state/back-fill-sub-run! frame-id epoch-id event
                                                     (rf.epoch.capture/sub-run-row event)))]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. Same failure-isolated fan-out + no-loop contract as the
        ;; render back-fill above.
        (notify-listeners! updated-record)))))

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
  map would accrete one permanent entry per ever-mounted instance, pruned ONLY on
  whole-frame destroy — unbounded per-frame heap over a long churning session
  (the time-travel scenario this surface serves). The eviction runs
  UNCONDITIONALLY of whether the back-fill found a live epoch: the entry is
  dead either way once the instance tears down (a fixture-teardown unmount with
  no settled epoch would strand an entry otherwise). The render-key is read
  from the same `:rf.view/render-key` tag the mount path uses
  (`record-render!`); a late mount-burst tail arriving after the prune
  harmlessly re-mints the entry.

  No-op (for the back-fill leg) when the frame has no settled epoch yet (an
  unmount before the first cascade — e.g. a fixture teardown) or when the
  target epoch has been evicted from the ring — `back-fill-unmount!` returns
  nil and we skip the re-notify."
  [frame-id event]
  (when rf.interop/debug-enabled?
    (when-let [epoch-id (rf.epoch.state/last-settled-epoch-id frame-id)]
      ;; Unmount has no structured row, so only retained raw trace is updated.
      (when-let [updated-record
                 (rf.epoch.state/back-fill-unmount! frame-id epoch-id event)]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. Same failure-isolated fan-out + no-loop contract as the
        ;; render / sub-run back-fill above.
        (notify-listeners! updated-record)))
    ;; Prune the unmounting instance's mount-attribution entry so
    ;; the map stays bounded across instance churn (NOT retained until
    ;; whole-frame destroy). Runs whether or not the back-fill found a live
    ;; epoch — the entry is dead once the instance unmounts.
    (rf.epoch.state/drop-render-key-mount-attribution!
      frame-id (-> event :tags :rf.view/render-key))))

(defn snapshot-terminal-destroy-evidence!
  "Snapshot a destroyed incarnation's terminal halted-destroy evidence while it
  is STILL the sole owner of its id-keyed epoch stores.

  `re-frame.frame/destroy-frame!` calls this (via `:epoch/snapshot-frame-
  destroyed`) BEFORE `dissoc-frame!`, while the frame still resolves under its
  id. The destroy holds the id's reservation until step 10 has finished, so no
  same-id incarnation exists between this snapshot and its publication.

  `halt` is the drain's evidence from the destroying event's boundary, nil for
  an immediate destroy: `{:frame-state :committed-at :trigger-event
  :dropped-count :last-event-id}`.

  Returns the evidence bundle `{:record :listener-snapshot}` consumed by
  `on-frame-destroyed!`, or nil when no epoch layer participates (production /
  debug disabled). `:record` is the `:halted-destroy` record when a pending
  destroy's boundary drop removed at least one event, else nil (Spec-Schemas
  §`:rf/epoch-record` §Outcomes): its trigger is the event the drain would
  have dequeued next, which never ran, so both frame-state slots hold the
  committed frame-state. `:listener-snapshot` is the `{cb-id → callback}`
  registry that record fans to, read at destroy time, so a listener registered
  in the post-dissoc window never receives A's terminal record; it is empty
  when there is no record.

  Schema digesting is intentionally omitted: A's schemas are already torn down
  and a bare-id digest could only resolve absent state."
  [frame-id halt]
  (when rf.interop/debug-enabled?
    (let [{:keys [frame-state committed-at trigger-event dropped-count
                  last-event-id]} halt
          halt-reason (cond-> {:operation     :rf.frame/drain-interrupted
                               :dropped-count dropped-count}
                        last-event-id (assoc :last-event-id last-event-id))
          record      (when (and (some? dropped-count) (pos? dropped-count))
                        (cond-> (rf.epoch.assembly/build-record
                                  frame-id frame-state frame-state []
                                  committed-at :halted-destroy halt-reason nil)
                          (vector? trigger-event)
                          (assoc :event-id      (first trigger-event)
                                 :trigger-event trigger-event)))]
      {:record            record
       :listener-snapshot (if record (rf.epoch.state/listeners-snapshot) {})})))

(defn on-frame-destroyed!
  "Publish a destroyed frame's terminal epoch record and drop its id-keyed
  stores.

  `terminal-evidence` is the bundle `snapshot-terminal-destroy-evidence!`
  captured BEFORE dissoc. This step publishes that snapshot rather than
  rebuilding the record, because after dissoc the id no longer resolves to the
  frame.

  If the snapshot bound a `:halted-destroy` record — the work a pending
  destroy dropped at its boundary — listeners receive it raw. The record is not
  stored: destroyed frames have empty history, so listener delivery is the only
  channel for this terminal record.

  The id-keyed store DROP (`state/cleanup-frame-owner!`) compares no owner
  token: the destroy holds the id's reservation until this step has finished,
  so no same-id incarnation can have claimed the stores, and everything under
  the id is A's. The drop runs UNCONDITIONALLY of terminal-evidence — cleanup
  authority is the reserved frame-id, not the snapshot bundle — so a throwing
  pre-dissoc snapshot hook (nil terminal-evidence) still frees A's history /
  buffer / last-settled-epoch / mount attribution, which nothing else would
  free under an id that is never reused. PUBLICATION of A's already-snapshotted
  terminal record and its structural trailers is INDEPENDENT of the drop AND
  conditional on a non-nil bundle — the evidence was bound to A's exact
  incarnation before dissoc and is A's to deliver; a nil bundle bound nothing
  and publishes nothing. All terminal emits use structural delivery: they
  arrive after step 8 released the frame's ring and no-emit flag, and an
  ordinary emit would recreate a ring under an id whose frame is gone (Spec 009
  §Retention boundary). Repeated destroys are idempotent (a re-destroyed frame
  has no boundary record, so nothing is owed)."
  [frame-id terminal-evidence]
  ;; Sole-condition `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; dead-code-eliminates this whole path (Spec 009 §Production builds). Two
  ;; concerns split INSIDE the gate: (1) STORE CLEANUP runs UNCONDITIONALLY of
  ;; terminal-evidence; (2) terminal-record PUBLICATION stays conditional on a
  ;; non-nil bundle. Neither is folded into the gate condition itself.
  (when rf.interop/debug-enabled?
    ;; (1) STORE CLEANUP — runs whether or not terminal-evidence is nil.
    ;; Cleanup authority is the reserved frame-id, NOT the snapshot bundle: a
    ;; throwing `:epoch/snapshot-frame-destroyed` hook yields nil
    ;; terminal-evidence (frame.cljc converts the throw to nil), but the
    ;; destroyed incarnation's id-keyed stores must STILL be dropped, or A's
    ;; history / buffer / last-settled-epoch / mount attribution outlive the
    ;; frame under its id. While this destroy holds the id's reservation no
    ;; other incarnation can own them, so the drop compares no owner token.
    (rf.epoch.state/cleanup-frame-owner! frame-id)
    ;; (2) TERMINAL-RECORD PUBLICATION — conditional on a bundle that bound a
    ;; record, and independent of the drop: the evidence was snapshotted
    ;; before dissoc and belongs to A's incarnation. It is
    ;; delivered before the destroy releases the id, so it never overlaps a
    ;; same-id incarnation; consumers treat it as HISTORICAL for A (Tool-Pair
    ;; late-record consumer rule).
    (when-let [record (:record terminal-evidence)]
      ;; Same trailer as normal commits, delivered
      ;; structurally: it arrives after A's ring was released, so no ring or
      ;; capture buffer may retain it under the dead id.
      (rf.trace/call-with-structural-delivery
        #(rf.epoch.assembly/emit-snapshotted! frame-id (:epoch-id record)
                                              (:event-id record) :halted-destroy))
      (deliver-listener-snapshot! record (:listener-snapshot terminal-evidence)))))
