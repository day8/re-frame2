(ns re-frame.epoch.listeners
  "Epoch-listener fan-out + frame-destroy hook.

  Two responsibilities live here:

    1. `notify-listeners!` — fan a built record out to every registered
       `register-epoch-listener!` callback. Each invocation is wrapped in a
       try/catch so one broken listener cannot break the runtime or
       block other listeners (Spec 009 §Listener invocation rules); a
       failing cb emits `:rf.epoch.cb/listener-exception` so devtools
       can surface the broken consumer (rf2-i5khp).

    2. `on-frame-destroyed!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/on-frame-destroyed` slot. Coordinates the four-step
       destroy contract (rf2-d656 + rf2-v0jwt + rf2-zzper):

         (a) mid-drain destroy detection → commit a `:halted-destroy`
             partial record (carrying the real pre-cascade `:db-before`
             + destroy-time `:db-after` snapshots threaded by
             `destroy-frame!`, per rf2-9neiq) to listeners (NOT to the
             ring buffer — `epoch-history` is read-empty post-destroy
             per rf2-d656).
         (b) emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb
             whose observed-frames set contained `frame-id`.
         (c) drop the per-frame ring buffer.
         (d) drop the in-flight capture buffer.

  Per the Phase-1 finding (rf2-0wi86): on-frame-destroyed straddles
  state (the three atoms it clears), capture (its mid-drain detection
  reads the cascade buffer), and assembly (it builds a partial
  record). This namespace is the rightful home — the destroy contract
  IS the listener-side surface for frame teardown, and the
  cross-cutting calls are now explicit cross-namespace requires
  rather than forward-declares within a single 1500-LoC file.

  Public re-frame.epoch facade fns (register-epoch-listener!,
  unregister-epoch-listener!, clear-epoch-listeners!, on-frame-destroyed!) delegate
  here; the listener-registry atom + record-observation bookkeeping
  live in `re-frame.epoch.state` (seam A)."
  (:require [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

(defn notify-listeners!
  "Fan `record` out to every registered `:rf/epoch-record` listener.

  Each cb is invoked once per record, wrapped in failure isolation:
  Per Spec 009 §Listener invocation rules listener failures don't
  stop the loop; per rf2-i5khp a failing cb emits a structured
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
  (let [frame-id (:frame record)]
    (doseq [[id f] (state/listeners-snapshot)]
      (state/record-observation! id frame-id)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) ex
          ;; Identity tag is the canonical qualified `:rf.epoch/id` (rf2-ifdsar):
          ;; the trace-tag layer spells the epoch id `:rf.epoch/id` everywhere
          ;; (matching the `:rf.epoch/restore-*` rows + `emit-snapshotted+outcome!`).
          ;; The bare `:epoch-id` is the RECORD-field spelling we read FROM
          ;; (`(:epoch-id record)`), kept bare as part of the deliberate
          ;; record/projection vocabulary — see Conventions §Trace/epoch identity.
          (trace/emit-error! :rf.epoch.cb/listener-exception
                             {:frame       frame-id
                              :cb-id       id
                              :rf.epoch/id (:epoch-id record)
                              :message     #?(:clj  (.getMessage ^Throwable ex)
                                              :cljs (.-message ex))
                              :recovery    :no-recovery}))))))

;; ---- post-settle render back-fill (rf2-qs6dl) -----------------------------

(defn record-render!
  "Attribute a post-settle render emit to the cascade that CAUSED it
  (rf2-qs6dl), refined for the mount-render case (rf2-vh1k3).

  A `:view/render` / `:rf.view/rendered` trace fires at React commit
  time — AFTER the causing cascade settled — so it cannot ride the
  in-flight cascade buffer (there is none). rf2-qs6dl back-filled it into
  the frame's most-recently-settled epoch; that is right for a genuine
  reactive re-render but wrong for a MOUNT render whose commit lands late
  (React batches a freshly-mounted component's render onto a later tick,
  so it can commit AFTER the first user cascade settles). Attributed to
  last-settled, a mount render leaks spuriously into the first post-mount
  cascade's `:renders` (the rf2-vh1k3 defect: parallel-frames' title-view
  appearing in the first counter-inc epoch though its subs never changed).

  `state/resolve-render-epoch` picks the right anchor: the newest epoch
  where the rendering view's OWN inputs changed (a real re-render rides
  its causing cascade exactly as rf2-qs6dl intends), else the view's
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
          ;; EP-0015 §15 + open-issue 6 (RULED, hardened): the back-fill
          ;; appends the RAW render event (to `:trace-events`) + the RAW
          ;; projected row (to `:renders`). The ring is causal replay
          ;; material, so it stays raw; the `:redact-fn` advanced override
          ;; runs projection-side only, inside
          ;; `projected-record`, so the off-box egress (Xray-MCP
          ;; `watch-epochs`, recorders) sees the redacted shape while the
          ;; ring + this listener fan-out deliver the raw record. No
          ;; per-back-fill redact invocation means no storage-side leak to
          ;; close and no non-idempotent-fn hazard.
          (when-let [updated (state/back-fill-render! frame-id target event
                                                      (capture/render-row event))]
            ;; Re-fan the corrected record so snapshot consumers re-read
            ;; the ring. The fan-out is failure-isolated per listener
            ;; (same contract as the settle-time fan-out); a render-driven
            ;; Xray pump (`:rf.xray/epoch-recorded`) is
            ;; `:rf.trace/no-emit?` so it commits no new epoch and cannot
            ;; loop back into this path.
            (notify-listeners! updated)))))))

;; ---- post-settle sub-run back-fill (rf2-wi900) ----------------------------

(defn record-sub-run!
  "Attribute a post-settle sub-run emit to the cascade that CAUSED it
  (rf2-wi900). The subs sibling of `record-render!`: a `:sub/run` /
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
      ;; EP-0015 §15 + open-issue 6 (RULED): the back-fill appends the RAW
      ;; sub-event (to `:trace-events`) + the RAW `:sub-runs` row. The ring
      ;; + listener fan-out deliver the raw record; the `:redact-fn`
      ;; advanced override runs projection-side only (inside
      ;; `projected-record`), so the off-box egress sees the redacted shape
      ;; (see `record-render!`).
      (when-let [updated (state/back-fill-sub-run! frame-id epoch-id event
                                                   (capture/sub-run-row event))]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. Same failure-isolated fan-out + no-loop contract as the
        ;; render back-fill above.
        (notify-listeners! updated)))))

;; ---- post-settle view-unmount back-fill (rf2-59hx3) -----------------------

(defn record-unmount!
  "Attribute a post-settle view-unmount emit to the cascade that CAUSED the
  teardown (rf2-59hx3). The view-teardown sibling of `record-sub-run!`: a
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

  PRUNE-ON-UNMOUNT (rf2-bgapd): after the back-fill, evict the unmounting
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
      ;; EP-0015 §15 + open-issue 6 (RULED): the back-fill appends the RAW
      ;; unmount event to `:trace-events`. The ring + listener fan-out
      ;; deliver the raw record; the `:redact-fn` advanced override runs
      ;; projection-side only (inside `projected-record`), so the off-box
      ;; egress (where Xray's VIEWS-step `unmounted-views-rows` consumes
      ;; the projected record) sees the redacted shape (see
      ;; `record-render!`). An unmount carries no structured projection row,
      ;; so only `:trace-events` is back-filled.
      (when-let [updated (state/back-fill-unmount! frame-id epoch-id event)]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. Same failure-isolated fan-out + no-loop contract as the
        ;; render / sub-run back-fill above.
        (notify-listeners! updated)))
    ;; rf2-bgapd — prune the unmounting instance's mount-attribution entry so
    ;; the map stays bounded across instance churn (NOT retained until
    ;; whole-frame destroy). Runs whether or not the back-fill found a live
    ;; epoch — the entry is dead once the instance unmounts.
    (state/drop-render-key-mount-attribution!
      frame-id (-> event :tags :rf.view/render-key))))

(defn on-frame-destroyed!
  "Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656)
  and rf2-v0jwt / rf2-9neiq §Outcomes (`:halted-destroy`):

    1. Mid-drain destroy detection — if `capture-buffers[frame-id]`
       holds buffered events at destroy time, this is a mid-drain
       destroy (the handler that called `destroy-frame!` was running
       inside the drain; its trace events were captured into the
       in-flight buffer). Notify epoch listeners with a partial
       `:halted-destroy` record carrying the run's traces AND the
       real `:frame-state-before` / `:frame-state-after` snapshots threaded
       by `destroy-frame!` (rf2-9neiq / rf2-3aizt1): `fs-before` is the
       pre-run snapshot (the whole frame-state — both partitions — the
       frame held before the in-flight event's run began, from the
       router-bound `frame/*run-frame-state-before*`); `fs-after` is the
       destroy-time state (the live frame-state value read at the top of
       `destroy-frame!`, before teardown — the partial run's
       already-committed writes survive in it). `build-record` derives the
       `:db-before` / `:db-after` app-db projections from them. This
       conforms the record to Spec-Schemas §`:rf/epoch-record` §Outcomes,
       which documents `:halted-destroy` as carrying the pre-run
       snapshot as `:frame-state-before` and the destroy-time/partial state
       as `:frame-state-after`. The record is delivered
       to LISTENERS ONLY — it is NOT appended to the ring buffer, because
       step 3 drops the destroyed frame's ring and `(rf/epoch-history
       destroyed)` returns `[]` per the rf2-d656 read-empty contract.
       Listeners (`register-epoch-listener!`) receive every record
       regardless of `:outcome` (Spec-Schemas §`:rf/epoch-record`
       §Restore semantics), so the listener fan-out IS the documented
       devtools-introspection channel for the destroyed-mid-drain halt.
    2. Emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb whose
       observed-frames set contains `frame-id`, then drop `frame-id`
       from each cb's entry so a re-registration of a same-keyed frame
       re-arms the silencing trace for a future destroy.
    3. Drop the destroyed frame's per-frame ring buffer so subsequent
       `(rf/epoch-history frame-id)` calls return the empty vector
       (the read-empty shape the contract commits to).
    4. Drop any in-flight capture buffer entry for `frame-id`
       (rf2-zzper) so a mid-drain destroy can't leak its pre-destroy
       events into the first cascade of the next same-keyed frame.

  `fs-before` / `fs-after` are the two frame-state snapshots `destroy-frame!`
  captured before the frame was removed (see `re-frame.frame/notify-epoch-
  listeners!`). Both are nil for an out-of-run destroy (no in-flight
  run → no `:halted-destroy` record committed at all). EP-0001
  (rf2-3aizt1, decision #2): the canonical snapshot unit is the whole
  frame-state; `build-record` derives the `:db-*` app-db projections.

  `committed-at` (rf2-bh56rc) is the destroying event's causal `:rf/time-ms`
  (the router-bound `frame/*run-time-ms*`, threaded through
  `destroy-frame!`), used for the `:halted-destroy` record's
  `:committed-at` so it is replayable per EP-0010 §Time rather than an
  ambient host-clock read at assembly time. nil outside a drain (no
  `:halted-destroy` record is committed there, so the value is moot).

  Called from `re-frame.frame/destroy-frame!` via the
  `:epoch/on-frame-destroyed` late-bind hook. Idempotent across
  repeated destroys of the same frame — once a cb's entry no longer
  contains the frame-id, no further trace fires for that pair, and
  the (already-cleared) ring-buffer / capture-buffer entries stay
  absent."
  [frame-id fs-before fs-after committed-at]
  (when interop/debug-enabled?
    ;; Step 1: mid-drain destroy detection. The capture-buffer holds
    ;; every emit tagged with this frame, including non-cascade emits
    ;; that fire OUTSIDE a drain (e.g. `:frame/created` at reg-frame
    ;; time). Distinguish a real mid-drain destroy from
    ;; registration-time tagalongs by gating on the presence of an
    ;; `:event/run-start` emit — that is the canonical "a cascade
    ;; started inside this drain" signal. When present, commit a
    ;; partial `:halted-destroy` record so devtools (Xray,
    ;; re-frame2-pair) receive the cascade context for the destroyed-
    ;; mid-drain case. Per rf2-9neiq the `:db-before` / `:db-after`
    ;; slots carry the real pre-cascade + destroy-time snapshots
    ;; `destroy-frame!` threaded (captured before the container was
    ;; torn down), so the record matches the documented contract. The
    ;; record is delivered to listeners
    ;; only — the ring buffer gets dropped in step 3 (rf2-d656).
    ;; Mid-drain detection reuses the canonical
    ;; `capture/in-flight-cascade?` gate (the same predicate the
    ;; post-settle render / sub-run back-fill routing uses) so the
    ;; 'is a cascade in flight?' question lives in one place. The
    ;; buffered events are read separately for the partial-record build.
    (let [buffered-events (state/buffer-for frame-id)]
      (when (capture/in-flight-cascade? frame-id)
        ;; Per EP-0015 §15 + open-issue 6 (RULED, hardened): the
        ;; halted-destroy partial record is delivered RAW to listeners
        ;; (the same posture as the :ok / :halted-depth ring records).
        ;; Epoch records are causal replay material, so they stay raw; the
        ;; `:redact-fn` advanced override runs projection-side only, inside
        ;; `projected-record`. A listener
        ;; that forwards off-box projects at its egress boundary.
        (let [record (assembly/build-record frame-id fs-before fs-after buffered-events
                                            committed-at
                                            :halted-destroy
                                            {:operation :rf.frame/destroyed-mid-drain})]
          ;; Per rf2-18g1w / rf2-jppad — the cascade-trailer pair (detailed
          ;; `:rf.epoch/snapshotted` `:outcome :halted-destroy` plus the
          ;; consumer-facing `:rf.epoch/outcome :blocked`). Shared with the
          ;; clean/halt settle via `assembly/emit-snapshotted+outcome!` so
          ;; the two-op trailer shape stays identical across both commit
          ;; paths.
          (assembly/emit-snapshotted+outcome! frame-id (:epoch-id record)
                                              (:event-id record) :halted-destroy)
          (notify-listeners! record))))
    (let [silenced-cbs (->> (state/observations-snapshot)
                            (keep (fn [[cb-id frames]]
                                    (when (contains? frames frame-id) cb-id)))
                            vec)]
      (doseq [cb-id silenced-cbs]
        (trace/emit! :rf.epoch.cb :rf.epoch.cb/silenced-on-frame-destroy
                     {:frame  frame-id
                      :cb-id  cb-id})))
    (state/drop-frame-observation! frame-id)
    ;; Drop the per-frame ring buffer; epoch-history returns [] from
    ;; here on. (`reset-frame! :app/main` calls destroy-frame! followed
    ;; by reg-frame, so the ring buffer for the new same-keyed frame
    ;; starts empty per Spec 002 §reset-frame!.)
    (state/drop-frame-history! frame-id)
    ;; Per rf2-zzper: also drop any in-flight capture buffer. A
    ;; mid-drain destroy that surfaces a halted record above leaves
    ;; the buffer behind (the partial-record commit doesn't harvest);
    ;; explicitly clear here so the next cascade against a same-keyed
    ;; frame starts from an empty buffer. Symmetric to the ring-buffer
    ;; drop above.
    (state/drop-frame-buffer! frame-id)
    ;; Per rf2-qs6dl: forget the last-settled epoch-id so a post-destroy
    ;; render emit (a torn-down component's final flush) can't back-fill
    ;; into a record belonging to the destroyed frame's history — which
    ;; was just dropped above anyway.
    (state/drop-last-settled-epoch! frame-id)
    ;; Per rf2-vh1k3 + rf2-dq2b7: forget every render-key's mount-epoch
    ;; anchor AND read-set for the destroyed frame so a re-registration
    ;; of a same-keyed frame (`reset-frame! :app/main`) re-mints both
    ;; from scratch. Both signals share the single `mount-attribution`
    ;; atom; one wipe replaces the former two.
    (state/drop-frame-mount-attribution! frame-id)))
