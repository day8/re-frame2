(ns re-frame.epoch.listeners
  "Epoch listener fan-out and frame teardown.

  Two responsibilities live here:

    1. `notify-listeners!` — fan a built record out to every registered
       `register-epoch-listener!` callback. Each invocation is wrapped in a
       try/catch so one broken listener cannot break the runtime or
       block other listeners; a
       failing cb emits `:rf.epoch.cb/listener-exception` so devtools
       can surface the broken consumer.

    2. `snapshot-terminal-destroy-evidence!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/snapshot-frame-destroyed` slot BEFORE dissoc, binding the
       dying incarnation's terminal evidence (halted record + silencing
       fan) to a bundle while it still solely owns its id-keyed stores.

    3. `on-frame-destroyed!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/on-frame-destroyed` slot AFTER dissoc. Coordinates the
       four-step destroy contract against the pre-dissoc bundle:

         (a) mid-drain destroy detection → deliver the `:halted-destroy`
             partial record (carrying the real pre-cascade `:db-before`
              and destroy-time snapshots) to listeners, not the ring.
         (b) emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb
             whose observed-frames set contained `frame-id`.
         (c) drop the per-frame ring buffer.
         (d) drop the in-flight capture buffer.

       Publication of (a)/(b) is independent of the compare-owned store
       drop (c)/(d), so a same-id successor claiming the stores cannot lose
       predecessor A's terminal evidence (rf2-vxgfnd.151).

  Listener registry and observation bookkeeping live in
  `re-frame.epoch.state`."
  (:require [re-frame.epoch.assembly :as rf.epoch.assembly]
            [re-frame.epoch.capture :as rf.epoch.capture]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.interop :as rf.interop]
            [re-frame.trace :as rf.trace]))

(defn- deliver-listener-snapshot!
  "Deliver `record` to an already-snapshotted listener generation.

  Observation stamping belongs to the caller: ordinary fan-out stamps before
  delivery; terminal destroy fan-out snapshots the silencing set and removes
  the dying incarnation's observation state atomically before delivery.

  `terminal?` marks the destroy fan-out of predecessor A's `:halted-destroy`
  record. A's registry slot may already belong to a same-id successor B by the
  time a listener throws (a terminal listener can itself install B), so A's
  `:rf.epoch.cb/listener-exception` diagnostic is delivered STRUCTURALLY —
  bypassing B's `:rf.trace/frame-no-emit?` policy and B's epoch capture — rather
  than resolved through B's bare-id policy (rf2-vxgfnd.152). Ordinary
  (non-terminal) fan-out keeps the live frame's no-emit policy."
  ([record listener-snapshot]
   ;; Terminal destroy delivery already removed observation ownership and must
   ;; not recreate it while fanning out the halted record.
   (deliver-listener-snapshot! record listener-snapshot (constantly true) false true))
  ([record listener-snapshot continue? record-observation?]
   (deliver-listener-snapshot! record listener-snapshot continue? record-observation? false))
  ([record listener-snapshot continue? record-observation? terminal?]
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
          (let [[callback-id {:keys [callback generation]}] (first entries)]
            ;; Stamp only the callback that is actually about to run. A later
            ;; listener suppressed by owner loss must not be reported observed.
            (when record-observation?
              (rf.epoch.state/record-observation! callback-id generation frame-id))
            (try
              (callback record)
              (catch #?(:clj Throwable :cljs :default) listener-error
               ;; A callback that destroyed the exact owner has an inert throw;
               ;; never emit its failure diagnostic against same-id B.
               (when (continue?)
                 (if terminal?
                   ;; A's terminal callback fault is A's own diagnostic. A same-id
                   ;; B that a listener just installed (possibly no-emit) must not
                   ;; suppress or capture it (rf2-vxgfnd.152).
                    (rf.trace/call-with-structural-delivery
                      #(emit-listener-exception! callback-id listener-error))
                    (emit-listener-exception! callback-id listener-error)))))
           (recur (next entries))))))))

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
  ([record]
   (notify-listeners! record (constantly true)))
  ([record continue?]
   (let [listener-snapshot (rf.epoch.state/listeners-snapshot)]
     (when (continue?)
       (deliver-listener-snapshot! record listener-snapshot continue? true)))))

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
                     (rf.epoch.state/back-fill-render! frame-id target-epoch-id event
                                              (rf.epoch.capture/render-row event))]
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

  No-op when the frame has no settled epoch yet (a sub-run before the
  first cascade) or when the target epoch has been evicted from the ring
  — `back-fill-sub-run!` returns nil and we skip the re-notify."
  [frame-id event]
  (when rf.interop/debug-enabled?
    (when-let [epoch-id (rf.epoch.state/last-settled-epoch-id frame-id)]
      ;; Store raw; projection remains the egress boundary.
      (when-let [updated-record
                 (rf.epoch.state/back-fill-sub-run! frame-id epoch-id event
                                           (rf.epoch.capture/sub-run-row event))]
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
  destroyed`) BEFORE `dissoc-frame!`. A same-id successor B can only be
  constructed after that dissoc, so it can never claim — and thereby drop — the
  buffer / observation ledger out from under this snapshot (rf2-vxgfnd.151).

  Returns the evidence bundle
  `{:record :listener-snapshot :silenced-cbs :baseline-silence-seq}` consumed by
  `on-frame-destroyed!`, or nil when no epoch layer participates (production /
  debug disabled). `:record` is the `:halted-destroy` partial record when the
  frame was mid-drain (a `:rf.event/run-start` is buffered), else nil.

  `:silenced-cbs` captures EXACT callback-generation identities — a
  `{cb-id → generation}` map, not a bare set (rf2-vxgfnd.265). The delayed
  silence is later decided PER identity: a cb re-registered to a fresh generation
  in the deferred window never observed this incarnation and must receive no
  stale signal, which a bare set could not express. The identities and their
  generations come from ONE consistent `state/snapshot-terminal-observers` read
  (rf2-vxgfnd.285) — each observing cb's generation is the OBSERVATION STAMP, so
  a replacement landing mid-snapshot can never attribute A's observation to a
  fresh generation H it never observed under (the exactness guarantee). For a
  mid-drain `:halted-destroy`, the SAME registry read additionally owes every
  listener the record will be fanned to (they observe it at delivery), so the
  owed generations and the record's listener fan can never disagree across two
  registry reads.

  `:baseline-silence-seq` is the terminal-silence counter snapshotted BEFORE
  dissoc. A same-id successor is constructable only AFTER dissoc, so any silence
  it emits is stamped strictly above this baseline — the monotonic evidence a
  paused predecessor uses to recognise a successor already silenced a cb (the
  A→B→nil ABA) rather than re-emitting the identical unqualified signal.

  Producing a non-nil bundle also OPENS this frame's deferred-silence window
  (`state/open-silence-lineage!`), keeping its terminal-silence marks alive until
  `on-frame-destroyed!` publishes and closes the window (the boundedness bracket).

  Schema digesting is intentionally omitted: A's schemas are already torn down
  and a bare-id digest could only resolve absent state or a same-id B."
  [frame-id fs-before fs-after committed-at]
  (when rf.interop/debug-enabled?
    (let [buffered-events   (rf.epoch.state/buffer-for frame-id)
          in-flight?        (some #(= :rf.event/run-start (:operation %))
                                  buffered-events)
          record            (when in-flight?
                              (rf.epoch.assembly/build-record
                                frame-id fs-before fs-after buffered-events
                                committed-at :halted-destroy
                                {:operation :rf.frame/destroyed-mid-drain}
                                nil))
          ;; ONE consistent (listeners, observed) read (rf2-vxgfnd.285): the
          ;; owed-observer generations come from the SAME registry read used to
          ;; qualify them, so a replacement mid-snapshot can never attribute A's
          ;; observation to a fresh generation it never observed under.
          {:keys [listeners observing]} (rf.epoch.state/snapshot-terminal-observers frame-id)
          ;; A mid-drain record is fanned to every current listener, so each owes
          ;; a silence under ITS live generation (from the same `listeners` read).
          listener-snapshot (if record listeners {})
          silenced-cbs      (cond-> observing
                              record (into (map (fn [[cb {:keys [generation]}]]
                                                  [cb generation]))
                                           listeners))
          baseline          (rf.epoch.state/current-terminal-silence-seq)]
      ;; Open the frame's deferred-silence window BEFORE dissoc so its
      ;; terminal-silence marks survive until this predecessor publishes; the
      ;; paired `close-silence-lineage!` runs in `on-frame-destroyed!`.
      (rf.epoch.state/open-silence-lineage! frame-id)
      {:record               record
       :listener-snapshot    listener-snapshot
       :silenced-cbs         silenced-cbs
       :baseline-silence-seq baseline})))

(defn on-frame-destroyed!
  "Publish a destroyed frame's terminal epoch evidence and drop its id-keyed
  stores.

  Two arities:

    * `[frame-id owner-token terminal-evidence]` — the destroy-recipe arity.
      `terminal-evidence` is the bundle `snapshot-terminal-destroy-evidence!`
      captured BEFORE dissoc; publishing that snapshot (rather than re-reading
      the now-shared id-keyed stores) is what keeps predecessor A's evidence
      intact after a same-id successor B has claimed those stores
      (rf2-vxgfnd.151).

    * `[frame-id owner-token fs-before fs-after committed-at]` — the direct-seam
      arity for tools / unit pins, where no same-id successor can race the
      stores: it snapshots at call time and delegates to the arity above.

  If a run-start was buffered, listeners receive a raw `:halted-destroy` record
  (pre-run + destroy-time whole-frame snapshots plus the destroying event's
  causal time). The record is not stored: destroyed frames have empty history,
  so listener delivery is the only channel for this terminal partial record.

  The delayed silence is decided PER snapshotted callback-generation identity
  (rf2-vxgfnd.265), and .285 makes that decision EXACT, LINEARIZABLE and BOUNDED.
  Store claim, callback re-arm, and terminal silence are DISTINCT events, so the
  coarse `cleanup-frame-owner!` result cannot gate the fan: a successor that
  claims the stores but delivers no epoch (A still owes the silence, losing the
  comparison notwithstanding); a successor that re-armed a cb then destroyed
  before A resumes (A must NOT re-emit the silence B already fired, even though A
  now wins the comparison); a cb re-registered to a fresh generation (must
  receive no stale signal). Each owed identity is published iff
  `state/claim-and-publish-delayed-silence!` grants it — the eligibility recheck
  (CURRENT generation, CURRENT live observers read fresh not a stale pre-loop set,
  any existing mark) plus the monotonic-seq RESERVATION run as ONE atomic claim
  under both ledger locks; the claim then RELEASES the locks and emits the
  GENERATION-QUALIFIED signal OUTSIDE them (rf2-8b9twg — the emit fans to
  arbitrary listeners, one of which may `dispatch-sync` and acquire a frame's
  `:drain-lock`, so it must not run under a ledger lock). The seq is the total
  order the observer relies on; two overlapping same-id publishers can never both
  claim. A reservation is rolled back only if the external envelope delivery
  itself throws. The owed identities are fanned in a deterministic (cb-id-ordered)
  sequence so a trace listener re-arming a LATER identity during an EARLIER
  silence sees a stable, linearizable order.

  The id-keyed store DROP is compare-owned (`state/cleanup-frame-owner!`): it
  runs only while `owner-token` still owns the stores, so stale A can never
  erase a fresh same-id B. That drop runs UNCONDITIONALLY of terminal-evidence —
  cleanup authority is the frame-id + owner-token, not the snapshot bundle — so a
  throwing pre-dissoc snapshot hook (nil terminal-evidence, PR #5939) still frees
  A's history / buffer / observation / last-settled-epoch / mount attribution and
  no same-id successor inherits them (rf2-hclxos). PUBLICATION of A's already-
  snapshotted terminal record and its structural trailers is INDEPENDENT of
  winning that comparison AND conditional on a non-nil bundle — the evidence was
  bound to A's exact incarnation before dissoc and is A's to deliver whether or
  not A still owns the stores (rf2-vxgfnd.151); a nil bundle bound nothing and
  publishes nothing. Live
  observation is re-read INSIDE each per-identity claim AFTER the compare-owned
  cleanup, so a winning cleanup has already dropped this incarnation's own stamps
  while a losing one leaves the successor's — exactly the re-arm evidence the
  claim consults. All terminal emits use structural delivery so a same-id B's
  frame-no-emit policy can neither suppress nor capture them. Repeated destroys
  are idempotent (a re-destroyed frame has no observers and no mid-drain record,
  so nothing is owed). The frame's deferred-silence window opened by
  `snapshot-terminal-destroy-evidence!` is closed here in a `finally`, reclaiming
  the frame's marks once its last outstanding predecessor resolves (BOUNDED)."
  ([frame-id owner-token fs-before fs-after committed-at]
   (on-frame-destroyed! frame-id owner-token
                       (snapshot-terminal-destroy-evidence!
                         frame-id fs-before fs-after committed-at)))
  ([frame-id owner-token terminal-evidence]
   ;; Sole-condition `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
   ;; dead-code-eliminates this whole path (Spec 009 §Production builds). Two
   ;; concerns split INSIDE the gate: (1) exact-owner STORE CLEANUP runs
   ;; UNCONDITIONALLY of terminal-evidence; (2) terminal-record/silencing
   ;; PUBLICATION stays conditional on a non-nil bundle. Neither is folded into
   ;; the gate condition itself.
   (when rf.interop/debug-enabled?
     ;; (1) EXACT-OWNER STORE CLEANUP — runs whether or not terminal-evidence is
     ;; nil. Cleanup authority is the frame-id + owner-token, NOT the snapshot
     ;; bundle: a throwing `:epoch/snapshot-frame-destroyed` hook yields nil
     ;; terminal-evidence (frame.cljc converts the throw to nil — PR #5939), but the
     ;; destroyed incarnation's id-keyed stores must STILL be dropped or a same-id
     ;; successor B inherits A's history / buffer / observation / last-settled-epoch
     ;; / mount attribution (rf2-hclxos). The drop stays compare-owned
     ;; (`cleanup-frame-owner!`): A erases ONLY its own stores while `owner-token`
     ;; still owns them, so a stale A that lost the comparison to a claimed same-id
     ;; B no-ops and leaves B untouched (fail-closed, no cross-incarnation wipe).
     ;; The return value is intentionally ignored for the silencing decision below —
     ;; winning this comparison is NOT the same fact as "no successor re-armed the
     ;; observers" (a claim without delivery loses it; a re-arm-then-destroy wins
     ;; it), which the per-identity claim resolves precisely.
     (rf.epoch.state/cleanup-frame-owner!
       frame-id owner-token
       (fn []
         ;; Data-only exact-owner transaction: no external callback runs
         ;; between owner comparison and the complete drop.
         (rf.epoch.state/drop-frame-observation! frame-id)
         (rf.epoch.state/drop-frame-history! frame-id)
         (rf.epoch.state/drop-frame-buffer! frame-id)
         (rf.epoch.state/drop-last-settled-epoch! frame-id)
         (rf.epoch.state/drop-frame-mount-attribution! frame-id)
         true))
     ;; (2) TERMINAL-RECORD + SILENCING PUBLICATION — conditional on a non-nil
     ;; bundle. A non-nil bundle means `snapshot-terminal-destroy-evidence!`
     ;; participated (debug on) and opened the deferred-silence window; that is the
     ;; exact condition under which we must publish AND close it. A nil bundle (the
     ;; snapshot hook threw, or no epoch layer) opened no window and bound no
     ;; evidence — it publishes and fabricates nothing (#5939), yet the store
     ;; cleanup above already ran.
     (when terminal-evidence
       (try
        (let [{:keys [record listener-snapshot silenced-cbs baseline-silence-seq]}
              terminal-evidence]
         ;; Publish A's terminal RECORD + trailers regardless of the cleanup
         ;; comparison — the evidence was snapshotted before dissoc and belongs to
         ;; A's incarnation (rf2-vxgfnd.151). A late predecessor terminal record may
         ;; arrive after a newer same-id epoch; it is HISTORICAL and consumers must
         ;; not treat it as the successor's current ring/focus (Spec 009 / Tool-Pair
         ;; late-record consumer rule).
         (when record
           ;; Same detailed/coarse trailer pair as normal commits, delivered
           ;; structurally: excluded from capture ownership and immune to a same-id
           ;; B's frame-no-emit policy.
           (rf.trace/call-with-structural-delivery
             #(rf.epoch.assembly/emit-snapshotted+outcome! frame-id (:epoch-id record)
                                                  (:event-id record) :halted-destroy))
           (deliver-listener-snapshot! record listener-snapshot))
         ;; PER-IDENTITY atomic-claim-THEN-PUBLISH silencing (rf2-vxgfnd.285 /
         ;; rf2-9bhne6 / rf2-8b9twg). Iterate the owed identities in a
         ;; deterministic cb-id order so the linearizable claim sees a stable
         ;; sequence. `claim-and-publish-delayed-silence!` reserves the one signal
         ;; under BOTH ledger locks (rechecking current generation, FRESH live
         ;; observers, and any existing mark, then writing the monotonic mark),
         ;; RELEASES the locks, and only THEN runs this external emit — so the
         ;; foreign trace fan-out (a blessed listener may `dispatch-sync`,
         ;; acquiring a frame's :drain-lock) never runs while a ledger lock is
         ;; held. That is what breaks the ledger↔:drain-lock AB-BA deadlock
         ;; rf2-9bhne6 introduced by emitting UNDER the locks (rf2-8b9twg).
         ;; REGISTRATION authority survives WITHOUT the lock by QUALIFYING the
         ;; emit with `observed-gen`: the reserved generation G is carried on the
         ;; payload, so a same-id replacement H landing in the reserve→emit window
         ;; is never mistaken as the silence's subject — a receiver whose current
         ;; generation for cb-id != the carried `:observed-gen` self-filters the
         ;; stale signal. OBSERVATION-continuum authority does NOT ride that
         ;; qualifier: a same-id SUCCESSOR frame re-arming cb-id in the same window
         ;; mints no generation, so `:observed-gen` still matches while the callback
         ;; is live again. The receiver discriminates that case inside the ONE
         ;; supported decision, `re-frame.epoch/epoch-silence-current?`, which
         ;; weighs registration identity AND observation continuum for
         ;; (cb-id, frame) under a single ledger snapshot (rf2-qg98y, made atomic
         ;; by rf2-uhouu). Only a
         ;; granted reservation emits; if the external
         ;; delivery throws, the reservation is rolled back (under silence-lock)
         ;; and the fault propagates.
         (doseq [[cb-id observed-gen] (sort-by (comp str key) silenced-cbs)]
           (rf.epoch.state/claim-and-publish-delayed-silence!
             frame-id cb-id observed-gen baseline-silence-seq
             ;; The silencing fact belongs to destroyed A too; never let a
             ;; same-id successor's trace policy suppress or capture it. The
             ;; `:observed-gen` qualifier is what lets the emit run outside the
             ;; ledger locks without reopening the G→H window (rf2-8b9twg).
             #(rf.trace/call-with-structural-delivery
                (fn []
                  (rf.trace/emit! :rf.epoch.cb :rf.epoch.cb/silenced-on-frame-destroy
                               {:frame frame-id :cb-id cb-id
                                :observed-gen observed-gen}))))))
        (finally
          ;; Close the deferred-silence window opened at snapshot time. When the
          ;; frame's last outstanding predecessor resolves, its marks are reclaimed.
          (rf.epoch.state/close-silence-lineage! frame-id)))))))
