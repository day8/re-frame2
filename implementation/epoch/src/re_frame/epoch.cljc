(ns re-frame.epoch
  "Per-frame epoch history and time-travel support.

  Every DEQUEUED EVENT's run-to-completion marks an epoch boundary — one
  `:rf/epoch-record` per event, not per drain. A drain that processes a
  parent event and
  the `:fx [[:dispatch …]]` child it queued commits TWO records. A machine
  macrostep (`:raise` / `:always` microsteps) runs inside one event and
  stays ONE epoch. The runtime records, per frame, an `:rf/epoch-record`
  with:

    :epoch-id       opaque, unique within a frame's history
    :frame          frame keyword
    :committed-at   the committing event's causal `:rf/time-ms`
    :event-id       the event keyword that triggered the cascade
    :trigger-event  the full event vector
    :db-before      app-db snapshot before the event ran
    :db-after       app-db snapshot after that event settled
    :trace-events   the raw trace stream that produced this epoch
    :sub-runs       structured projection of subscription activity
    :renders        structured projection of render activity
    :effects        structured projection of fx-walk activity

  Records are kept in a per-frame ring buffer (default depth 50,
  configurable via `(rf/configure! {:epoch-history {:depth N}})`). Older
  records are evicted when the buffer is full.

  The entire epoch-history machinery is gated on `interop/debug-enabled?`,
  the same compile-time goog-define as the trace surface. Production
  builds elide the epoch implementation and its recorded data.

  Listeners receive each raw assembled record after the storage attempt.
  With history depth 0, listeners still receive records while the ring stays
  empty.

  Restore (`restore-epoch!`) rewinds a frame to the named epoch's
  canonical `:frame-state-after` — the WHOLE frame-state, with app-db
  and runtime-db as two separate partitions reinstalled in ONE atomic
  write. The retained
  `:db-before` / `:db-after` are OPTIONAL app-db projections of that
  frame-state, not the restore target itself. Refused restores emit a
  structured trace and leave the frame state unchanged."
  (:require [re-frame.epoch.assembly :as rf.epoch.assembly]
            [re-frame.epoch.capture :as rf.epoch.capture]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]))

;; ---- configuration --------------------------------------------------------
;;
;; Defaults, state, and boundary validation live in `re-frame.epoch.state`.

(defn configure!
  "Update the epoch-history configuration. Supported keys:

    :depth              N — non-negative integer; ring-buffer depth per
                        frame. 0 disables recording (assembled records can
                        still fire on listeners but nothing lands in the
                        ring buffer). The bound applies to the LIVE rings,
                        not merely to future appends: lowering it prunes
                        each frame's existing history to its newest N
                        records before this call returns, so the excess is
                        gone from `epoch-history` / `projected-history` and
                        is no longer a valid `restore-epoch!` /
                        `replay-epoch!` target. Depth 0 therefore empties
                        every ring outright, and re-enabling a positive
                        depth starts from an empty history rather than
                        resurrecting the pre-disable records.
    :trace-events-keep  N — non-negative integer; cap how many of the
                        MOST-RECENT records per frame retain their raw
                        `:trace-events` vector. Older records keep the cheap
                        structured projections (`:sub-runs` / `:renders` /
                        `:effects`) but drop `:trace-events` to bound memory.
                        Defaults to a fixed 50 — chosen to equal the shipped
                        `:depth` default so trace + epoch evict atomically. It
                        is a fixed default, NOT one that tracks a configured
                        `:depth`: set `{:depth 100}` and `:trace-events-keep`
                        stays 50 unless you also set it. Pass a smaller value
                        (e.g. 5) to bound dev-session heap more aggressively.
    :redact-fn          fn? or nil. An advanced projection-side override.
                        When non-nil the
                        framework invokes the fn ONCE per record at the
                        OFF-BOX EGRESS boundary — inside `projected-record`,
                        AFTER the frame/profile `project-egress` projection —
                        NOT at storage time. The ring buffer and every
                        `register-epoch-listener!` listener receive the RAW
                        record. Epoch records are causal replay material, and
                        mutating them at rest would corrupt the
                        replay contract. The fn is the rare advanced escape
                        for an app that records material the declaration-driven
                        projection cannot prove (a sensitive slot no frame /
                        schema declaration covers); ordinary redaction needs
                        only the frame's `:sensitive` / `:large` classification
                        plus the per-slot schema properties machine /
                        resource data carry, which `projected-record` already
                        applies. A throwing
                        fn emits `:rf.warning/epoch-redact-fn-exception` and
                        falls back to the projected (frame/profile-redacted)
                        record. Passing `nil` clears any previously-installed
                        fn. CAVEAT: the fn runs only on the projected egress
                        copy; it cannot affect `restore-epoch!` fidelity (the
                        ring stays raw).

  Invalid `:depth` / `:trace-events-keep` (not a non-negative integer) and
  malformed `:redact-fn` (not `fn?` / `nil`) are silently dropped at the
  boundary."
  [opts]
  (rf.epoch.state/merge-config! opts))

(defn current-config
  "Return the current epoch-history configuration map. Public for tests
  and tools that want to display the current depth."
  []
  (rf.epoch.state/current-config))

;; ---- the per-frame ring buffer --------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history": last N epochs per frame.
;; Stored as a map of frame-id → vector (oldest-first). New records append
;; to the back; the front evicts when the buffer exceeds the configured
;; depth. The atom + ring-buffer mutators live in `re-frame.epoch.state`.

(defn epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs (or when
  depth is 0, which disables recording — including records retained
  before the depth was lowered; see `configure!`)."
  [frame-id]
  (rf.epoch.state/history-for frame-id))

(defn clear-history!
  "Drop every recorded epoch for every frame. Test fixtures use this.

  Also drop in-flight capture buffers so a later event cannot harvest traces
  left by an interrupted test or run."
  []
  (rf.epoch.state/reset-histories!)
  (rf.epoch.state/reset-capture-buffers!)
  nil)

;; ---- listener registry ----------------------------------------------------
;;
;; The listener / observed-frames atoms and their low-level CRUD live in
;; `re-frame.epoch.state`; the facade keeps the public docstrings and the
;; fan-out / failure-isolation policy.

(defn register-epoch-listener!
  "Register a callback fired for each assembled event epoch record.
  The id can be any comparable value; passing the
  same id twice replaces the callback.

  The callback receives a fully-formed record with `:db-after`,
  `:sub-runs`, `:renders`, `:effects`, and `:trace-events` populated.
  Storage is attempted before the callback runs. At depth 0 the callback
  still receives the record even though the ring remains empty.

  Listener exceptions are caught and isolated; one broken listener
  cannot break the runtime or block other listeners.

  Returns the id."
  [id f]
  (rf.epoch.state/put-listener! id f))

(defn unregister-epoch-listener!
  "Remove the listener registered under id."
  [id]
  (rf.epoch.state/drop-listener! id))

(defn epoch-silence-current?
  "THE supported receiver decision for a `:rf.epoch.cb/silenced-on-frame-destroy`
  signal. Pass the signal's `:tags` map straight back:

      (rf/register-listener! :trace ::watch
        (fn [ev]
          (when (and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                     (rf/epoch-silence-current? (:tags ev)))
            ;; the silence names a CURRENT fact — act on it
            ...)))

  True when the silence still describes the live callback: the carried
  `:observed-gen` is still the generation registered under `:cb-id`, AND that
  registration is not observing `:frame` right now. False otherwise — including
  a `nil`/absent `:observed-gen`, which can never name a live registration.

  ONE ATOMIC DECISION, not two composable reads (rf2-uhouu). The decision needs
  two facts about the listener ledger, and it takes both from a SINGLE consistent
  snapshot:

    * REGISTRATION identity — a same-id replacement or an
      `(rf/unregister-listener! :epoch id)` drop in the reserve→emit window makes
      a DIFFERENT generation current, so the reserved generation's silence no
      longer names the current callback.
    * OBSERVATION continuum — a same-id SUCCESSOR frame re-arms a callback by
      DELIVERY, and a delivery mints NO generation (rf2-qg98y), so the carried
      `:observed-gen` still matches while the callback is receiving records
      again. Registration identity alone would accept a silence for a LIVE
      callback.

  Reading those two facts through two separate queries — the shape this replaces
  — is NOT linearizable: a replacement or drop landing between them reads as
  generation-still-matches AND not-observing, accepting a signal for a
  registration that is already superseded, a verdict no single point in time ever
  held. The former `epoch-listener-generation` / `epoch-listener-observing?` pair
  was therefore retired rather than kept beside this fn: exposing the halves is
  exposing the race. Per Spec 009 §The delayed-silence emission linearization
  law."
  [tags]
  (rf.epoch.state/silence-current? (:frame tags) (:cb-id tags) (:observed-gen tags)))

(defn clear-epoch-listeners!
  []
  (rf.epoch.state/reset-listeners!))

;; ---- per-cascade trace capture --------------------------------------------
;;
;; The drain runs traces through `re-frame.trace/emit!` which fans out to
;; every registered listener. We register an internal listener that
;; appends every event into a per-cascade buffer; when the cascade
;; settles, the buffer is harvested and projected into the structured
;; record slots.
;;
;; The buffer is keyed by frame-id so concurrent drains across frames
;; don't co-mingle. Within a frame, drain-execution is single-threaded
;; (per Spec 002 §Run-to-completion) so no further locking is needed.
;; Buffer storage lives in `re-frame.epoch.state`; capture and projection
;; helpers live in `re-frame.epoch.capture`.

;; ---- per-event settle hook ------------------------------------------------

(defn- commit-record!
  "Build, store, and fan out one raw `:rf/epoch-record`.

  `events` is the harvested capture buffer. `trigger-event` supplies the
  trigger for a halt whose event never ran and therefore has no
  `:event/run-start`; normal settles derive it from `events`.

  Raw storage is required for replay. Projection, including the configured
  `:redact-fn`, happens only at off-box egress. `committed-at` is the causal
  token's `:rf/time-ms`, not an assembly-time clock read."
  [frame-id frame-state-before frame-state-after events committed-at outcome
   halt-reason trigger-event exact-owner-token]
  (let [owner-token (or exact-owner-token
                        (rf.frame/frame-incarnation-token frame-id))
        continue?  #(or (nil? exact-owner-token)
                        (rf.frame/event-continuation-live?
                          frame-id exact-owner-token))]
    ;; Claim rechecks liveness under the same owner lock used by terminal
    ;; cleanup; a check-then-claim race cannot resurrect stale A ownership.
    (when (and owner-token
               (rf.epoch.state/claim-frame-owner! frame-id owner-token continue?))
      ;; Resolve the only callback-bearing assembly input before building. A
      ;; digest hook that destroys A (whether it returns or throws) fences every
      ;; state write below.
      (when (continue?)
        (let [schema-digest (rf.epoch.assembly/current-schema-digest frame-id continue?)]
          (when (continue?)
            ;; Whole-frame snapshots are restore units; app-db slots are
            ;; projections. The supplied digest keeps build-record data-only.
            (let [base-record (rf.epoch.assembly/build-record
                                frame-id frame-state-before frame-state-after events
                                committed-at outcome halt-reason schema-digest)
                  ;; Pin the explicit trigger only when the buffer did not
                  ;; resolve one (a halting event that never reached run-start).
                  record (cond-> base-record
                           (and trigger-event (not (:event-id base-record)))
                           (assoc :event-id      (first trigger-event)
                                  :trigger-event trigger-event))
                  ;; Record + async anchor publish as one exact-owner
                  ;; transaction. Cleanup-before-commit and B-before-commit
                  ;; reject; commit-before-cleanup is erased by cleanup.
                  published? (and (continue?)
                                  (rf.epoch.state/commit-frame-owner-record!
                                    frame-id owner-token record))]
              ;; The optional cascade aggregator is callback-bearing. Its
              ;; result and every later trailer are inert after owner loss.
              (when (and published? (continue?))
                (when-let [capture-for-epoch! (rf.late-bind/get-fn-cached
                                                :trace.cascade/capture-for-epoch!)]
                  (try
                    (capture-for-epoch! frame-id (:epoch-id record) (:event-id record) events)
                    (catch #?(:clj Throwable :cljs :default) _ nil))))
              (when (and published? (continue?))
                (rf.epoch.assembly/emit-snapshotted+outcome!
                  frame-id (:epoch-id record) (:event-id record) outcome continue?))
              (when (and published? (continue?))
                (rf.epoch.listeners/notify-listeners! record continue?))
              (when (and published? (continue?)) record))))))))

(defn settle!
  "Settle one dequeued event, not an entire drain.

  Parent and child events in one drain produce separate records; machine
  microsteps within one handler remain in that handler's record. The two
  frame-state arguments are whole-frame snapshots, and `committed-at` is the
  event envelope's causal time.

  The router arity includes `settling-dispatch-id`, allowing capture to discard
  a rejected dispatch's traces without consuming a queued child's marker. An
  empty harvest produces no misleading `:ok` record. A depth halt whose event
  never ran uses `commit-halt-record!`, which can commit an explicit trigger
  despite an empty buffer. Explicit outcomes carry a structured halt reason;
  when no trigger can be recovered, trigger slots are omitted rather than nil."
  ([frame-id frame-state-before frame-state-after committed-at]
   (settle! frame-id frame-state-before frame-state-after committed-at :ok nil nil))
  ([frame-id frame-state-before frame-state-after committed-at settling-dispatch-id]
   (settle! frame-id frame-state-before frame-state-after committed-at :ok nil settling-dispatch-id))
  ([frame-id frame-state-before frame-state-after committed-at outcome halt-reason]
   (settle! frame-id frame-state-before frame-state-after committed-at outcome halt-reason nil))
  ([frame-id frame-state-before frame-state-after committed-at outcome halt-reason settling-dispatch-id]
   (settle! frame-id frame-state-before frame-state-after committed-at
            outcome halt-reason settling-dispatch-id nil))
  ([frame-id frame-state-before frame-state-after committed-at outcome halt-reason
    settling-dispatch-id {:keys [exact-owner-token]}]
   (when rf.interop/debug-enabled?
     (if (and exact-owner-token
              (not (rf.frame/event-continuation-live?
                     frame-id exact-owner-token)))
       ;; A's run-start was already harvested by A's synchronous destroy hook;
       ;; tail traces may now sit beside fresh same-id B work. Remove only A's
       ;; dispatch-id evidence and never claim/commit B's epoch stores.
       (rf.epoch.state/drop-dispatch-buffer-events! frame-id settling-dispatch-id)
       ;; Take only the settling event's traces, leaving a queued child's
       ;; marker in the buffer for its own settle. The envelope id also scopes
       ;; the no-run-start branch so a rejected or aborted dispatch's own
       ;; error trace is dropped (not returned) rather than committed as a fake
       ;; `:ok` epoch.
       (let [events (rf.epoch.state/harvest-buffer-for-event! frame-id settling-dispatch-id)]
         ;; An empty capture has no event context. Rejected dispatches are
         ;; suppressed here; never-run depth halts use `commit-halt-record!`.
         (when (seq events)
           (commit-record! frame-id frame-state-before frame-state-after events
                           committed-at outcome halt-reason nil
                           exact-owner-token)))))))

(defn- commit-halt-record!
  "Commit a halt record for an event that never ran.

  Earlier events in the drain already committed and remain durable. Because
  the halting event has no run trace, this path uses its explicit event vector
  and does not skip an empty capture. Both frame-state snapshots use the last
  settled record's `:frame-state-after`; the router's live value is only the
  fallback when no record has settled. The snapshots are equal because the
  halted event made no write. Residual capture is cleared before commit, and
  `committed-at` comes from the halted event's already-stamped envelope.

  ONE frame-state parameter, not a before/after pair (rf2-6r9j.75). The hook
  once took both and read neither — `commit-record!` receives the SAME value in
  both slots, sourced from the last settled record (or, on a first-cascade halt,
  from this fallback). A second slot could only ever carry a value this path
  discards, so the pair implied two meaningful snapshots the halt never had.

  rf2-vxgfnd.154 — EXACT-INCARNATION halt commit. `exact-owner-token` is A's
  event-owner token (its `:drain-lock`); the router threads it so a depth-error
  listener that destroyed A and published a same-id B cannot have this terminal
  commit steal B's stores. The buffer harvest AND the record build/commit are
  gated on A's live continuation, and `commit-record!` claims A's exact token
  rather than resolving the current bare-id owner. Once A is lost the commit
  neither harvests B's capture buffer nor commits into B's history; when A
  retains ownership (the ordinary depth halt) behaviour is unchanged. A nil
  token preserves the historical bare-id contract for any non-exact caller.
  Kept single-arity (not multi-arity) with the `(when interop/debug-enabled? …)`
  outermost so the epoch's trace-event-projection keyword references stay DCE-
  able in production (multi-arity perturbed Closure's `:advanced` reachability —
  the rf2-cprm0q elision trap)."
  [frame-id frame-state-after committed-at outcome halt-reason
   trigger-event exact-owner-token]
  (when rf.interop/debug-enabled?
    (when (or (nil? exact-owner-token)
              (rf.frame/event-continuation-live? frame-id exact-owner-token))
      (let [events (rf.epoch.state/harvest-buffer! frame-id)
            ;; Source the durable value from the same snapshot restore uses,
            ;; rather than trusting the router's live re-read. Fall back to
            ;; the router-passed value when no `:ok` epoch has landed yet
            ;; (depth-exceed on the first cascade).
            last-id     (rf.epoch.state/last-settled-epoch-id frame-id)
            last-record (when last-id
                          (->> (rf.epoch.state/history-for frame-id)
                               (some (fn [r] (when (= last-id (:epoch-id r)) r)))))
            fs          (if last-record
                          (:frame-state-after last-record)
                          frame-state-after)]
        (commit-record! frame-id fs fs events
                        committed-at outcome halt-reason trigger-event
                        exact-owner-token)))))

;; ---- restore --------------------------------------------------------------
;;
(defn restore-epoch!
  "Rewind the frame to the named epoch's canonical `:frame-state-after`
  — the WHOLE frame-state, reinstalling app-db AND runtime-db as two
  separate partitions in one atomic write (reviving
  machine snapshots, the route slice, elision declarations, and SSR
  metadata, not just the app-db partition). `:frame-state-after` is the
  only restore source — every record `build-record` emits carries it; the
  retained `:db-after` is an OPTIONAL app-db projection for tool diffs,
  never a restore source. Emits `:rf.epoch/restored` on success.

  Failure modes (each is a no-op on the frame-state and emits a
  structured error trace):

    :rf.error/no-such-handler          (kind :frame) — frame not registered,
                                         OR the resolved incarnation was
                                         destroyed / replaced by a same-id
                                         successor at ANY point in the restore
                                         transaction — during precondition
                                         sampling, between validation and the
                                         write, or across the post-write
                                         anchoring/telemetry tail (the whole
                                         restore is one EXACT-incarnation
                                         transaction — a successor is left
                                         byte-for-byte untouched; rf2-bjh6y,
                                         rf2-qfrh4)
    :rf.epoch/restore-during-drain     — called while drain is in flight
    :rf.epoch/restore-unknown-epoch    — epoch-id not in current history
    :rf.epoch/restore-non-ok-record    — target epoch's :outcome is not :ok
                                         (halted records carry partial state and
                                         are not valid restore targets)
    :rf.epoch/restore-schema-mismatch  — db-after no longer validates
    :rf.epoch/restore-missing-handler  — referenced registration absent
    :rf.epoch/restore-version-mismatch — machine snapshot version drift

  Returns `true` on success, `false` on any failure."
  [frame-id epoch-id]
  (if-not rf.interop/debug-enabled?
    false
    (let [{:keys [outcome epoch op tags incarnation-token]}
          (rf.epoch.tool-pair/check-restore-preconditions! frame-id epoch-id)]
      (case outcome
        ;; Carry the EXACT incarnation token the preconditions resolved against
        ;; to the write boundary, so a same-id successor seated in between never
        ;; receives this epoch's state (rf2-bjh6y).
        :ok   (rf.epoch.tool-pair/perform-restore! frame-id incarnation-token epoch)
        :fail (do (rf.epoch.tool-pair/emit-precondition-failure! op tags)
                  false)))))

;; ---- replay (Tool-Pair §Replay) --------------------------------------------
;;
(defn replay-epoch!
  "Re-drive the named retained epoch's recorded event through `frame-id`'s
  own handlers, in ONE call, as a faithful-or-fail-loud strict replay
  (Tool-Pair §Replay). The record is resolved in-process and its replay
  material is folded into the dispatch opts here — the raw argument-bearing
  `:trigger-event` (off-box egress only ever shows its args as
  `:rf/redacted`), the post-generation `:rf.cofx` token under
  `:rf.cofx/mint-policy :strict`, and the record's own serializable
  `:fx-overrides` / `:interceptor-overrides`. Nothing is exported and
  nothing is copied by hand.

  Source and target frame are the SAME frame: the record is read from
  `frame-id`'s history and the event is dispatched into `frame-id`. Replay
  runs against the frame's CURRENT state and code — it does not restore
  first (compose with `restore-epoch!` for that), any external effect the
  handler emits fires again, the frame's live per-frame config applies, and
  the replayed dispatch records a NEW ordinary epoch. Re-presenting the
  recorded `:rf/time-ms` makes that epoch's `:committed-at` equal the
  original's.

  `opts` (3-arity) is an ordinary dispatch-opts map for the slots replay
  does not own — `:origin`, `:source`, `:trace-id`; a value it carries
  under `:frame`, `:rf.cofx`, `:rf.cofx/mint-policy`, `:fx-overrides` or
  `:interceptor-overrides` is discarded.

  Returns a structured envelope, never a bare boolean:

    {:ok? true  :frame <id> :source-epoch-id <id> :event-id <kw>
     :epoch-id <the replayed dispatch's own new epoch; nil if not retained>}
    {:ok? false :reason <kw> :frame <id> :epoch-id <id> …tags}

  Every refusal is decided BEFORE anything is dispatched, and returns the
  envelope only (no trace is emitted for it):

    :rf.error/no-such-handler (kind :frame)   — frame not registered / destroyed
    :rf.epoch/replay-during-drain             — called while a drain is in flight
    :rf.epoch/replay-unknown-epoch            — id not in the frame's current
                                                history (`:history-size`)
    :rf.epoch/replay-non-replayable-record    — `:cause` is `:halted` (outcome
                                                not `:ok`; `:outcome` /
                                                `:halt-reason` ride along),
                                                `:synthetic` (a
                                                `replace-frame-state!` record),
                                                `:missing-trigger-event` or
                                                `:missing-replay-token`
    :rf.epoch/replay-unreplayable-fx-override — a recorded `:fx-overrides` entry
                                                is the opaque `:rf/fn-override`
                                                sentinel (`:fx-ids`)

  A declared recordable fact ABSENT from the recorded token is NOT a
  refusal: the dispatch runs and fails with the canonical
  `:rf.error/missing-required-cofx` hard error, exactly as any `:strict`
  dispatch does — `replay-epoch!` neither catches it nor mints.

  Returns `false` when the epoch surface is elided (`interop/debug-enabled?`
  false) — the same inert value `restore-epoch!` returns."
  ([frame-id epoch-id] (replay-epoch! frame-id epoch-id nil))
  ([frame-id epoch-id opts]
   (if-not rf.interop/debug-enabled?
     false
     (let [{:keys [outcome epoch reason tags]}
           (rf.epoch.tool-pair/check-replay-preconditions! frame-id epoch-id)]
       (case outcome
         :ok   (rf.epoch.tool-pair/perform-replay! frame-id epoch opts)
         :fail (merge {:ok? false :reason reason :frame frame-id :epoch-id epoch-id}
                      tags))))))

;; ---- replace-frame-state! (Tool-Pair §Pair-tool writes) -------------------
;;
;; Dev-only state injection outside the dispatch loop. Present partition keys
;; replace their partition; absent keys preserve it. Every successful write
;; records a synthetic epoch so it can be undone, which is why depth 0 refuses
;; the operation rather than installing state without an undo anchor.

(defn- record-synthetic-replace-epoch!
  "Record and fan out one synthetic pair-tool epoch, as an EXACT-incarnation
  transaction keyed on `incarnation-token` — the token the replace's
  preconditions resolved and its physical write already committed under
  (rf2-gj2bo, following the `commit-record!` pattern above). Claiming
  whichever same-id token happens to be CURRENT is the defect this guards
  against: after A's write has linearized, a same-id successor B seated at
  any callback boundary must never inherit A's synthetic record, ring entry,
  last-settled anchor, trace, or listener delivery. So the claim, the digest
  resolution, the record + anchor commit (`commit-frame-owner-record!`, one
  owner-fenced transaction), and each callback-bearing tail (trace emit,
  listener fan-out) all revalidate exact ownership; owner loss STOPS the
  remaining bookkeeping rather than RETARGETING it onto B.

  The record remains raw for replay and becomes the frame's last-settled
  attribution anchor. With no application event in flight, `:committed-at`
  uses `epoch-now-ms`: a durable wall-clock value, not the elapsed-time clock."
  [frame-id incarnation-token frame-state-before frame-state-after]
  (let [continue? #(rf.frame/event-continuation-live? frame-id incarnation-token)]
    (when (and incarnation-token
               (rf.epoch.state/claim-frame-owner! frame-id incarnation-token continue?))
      ;; Resolve the only callback-bearing assembly input before building —
      ;; a digest hook that churns A to B fences every state write below.
      (when (continue?)
        (let [schema-digest (rf.epoch.assembly/current-schema-digest frame-id continue?)]
          (when (continue?)
            (let [record     (assoc (rf.epoch.assembly/build-record frame-id
                                                           frame-state-before
                                                           frame-state-after []
                                                           (rf.interop/epoch-now-ms)
                                                           :ok nil schema-digest)
                                    :event-id      :rf.epoch/db-replaced
                                    :trigger-event [:rf.epoch/db-replaced])
                  ;; Record + anchor publish as one exact-owner transaction:
                  ;; cleanup-before-commit and B-before-commit reject,
                  ;; commit-before-cleanup is erased by cleanup.
                  published? (and (continue?)
                                  (rf.epoch.state/commit-frame-owner-record!
                                    frame-id incarnation-token record))]
              (when (and published? (continue?))
                (rf.trace/emit! :rf.epoch :rf.epoch/db-replaced
                             {:frame       frame-id
                              :rf.epoch/id (:epoch-id record)}))
              (when (and published? (continue?))
                (rf.epoch.listeners/notify-listeners! record continue?))
              (when (and published? (continue?)) record))))))))

(defn- perform-replace!
  "Carry out a frame-state patch after preconditions pass, FENCED to the
  exact frame incarnation the preconditions resolved against
  (`incarnation-token`, from `check-replace-frame-state-preconditions!` —
  rf2-gj2bo, mirroring `perform-restore!`).

  Liveness is checked again at the write boundary, against the EXACT token
  rather than the bare id: a same-id successor seated between validation and
  this write can therefore never receive the patch — the gate refuses with
  the SAME canonical `:rf.error/no-such-handler` failure a destroyed-frame
  race produces, and `write-frame-state!` (core's exact-incarnation 3-arity write)
  closes the post-liveness half of the window. The write result settles the
  remaining teardown race: nil means the exact incarnation was lost before
  the write landed (destroyed, or reseated), while any non-nil changed-key
  set, including empty, is a successful live-incarnation write. Failure is
  reported before telemetry, listener fan-out, or a synthetic epoch can claim
  success. Once the exact-incarnation write HAS linearized, the public result
  stays `true`; the synthetic bookkeeping that follows is itself
  token-fenced (`record-synthetic-replace-epoch!`), so post-write owner loss
  STOPS the tail rather than retargeting it onto a successor.

  The coherent read (`frame-state-before`), the physical write, and the synthetic-epoch
  bookkeeping run under the frame's `:drain-lock` (`serialize-tool-write!`), so
  no event transition can interleave between the `frame-state-before` read and the
  write's own re-read. `frame-state-after` is therefore the EXACT value installed — the
  synthetic `:frame-state-before` / `:frame-state-after` describe the physical
  transition, and a concurrent event's update to an omitted partition is never
  lost or silently reverted (rf2-3fc89f.4). A replace invoked reentrantly from
  the active drainer refuses with `:rf.epoch/replace-during-drain`."
  [frame-id incarnation-token build-frame-state-after write-frame-state!]
  (rf.epoch.tool-pair/serialize-tool-write!
    frame-id
    :rf.epoch/replace-during-drain
    {:frame frame-id}
    (fn []
      (if-not (rf.frame/event-continuation-live? frame-id incarnation-token)
        ;; The incarnation these preconditions resolved against is no longer
        ;; live — a same-id SUCCESSOR was seated (or the frame was destroyed /
        ;; is being torn down) between validation and this write. Refuse via
        ;; the SAME canonical no-such-handler failure a destroyed-frame write
        ;; race uses (rf2-gj2bo). The successor stays byte-for-byte untouched;
        ;; no success telemetry fires.
        (do (rf.epoch.tool-pair/emit-precondition-failure! :rf.error/no-such-handler
                                                  {:kind :frame :frame frame-id})
            false)
        (let [;; Record the same coherent whole-frame value the write installs.
              ;; Read under the drain lock so the write's own re-read (which
              ;; preserves omitted partitions) sees this same state — frame-state-after
              ;; then equals the installed value. The write goes through the
              ;; EXACT-INCARNATION arity: nil means the incarnation was lost
              ;; after the gate (destroyed, or reseated — the post-liveness
              ;; race); an empty set is still a successful no-op write against
              ;; the live incarnation.
              frame-state-before (rf.frame/frame-state-value frame-id)
              frame-state-after  (build-frame-state-after frame-state-before)
              changed-keys       (write-frame-state!)]
          (if (nil? changed-keys)
            (do (rf.epoch.tool-pair/emit-precondition-failure! :rf.error/no-such-handler
                                                      {:kind :frame :frame frame-id})
                false)
            (do (record-synthetic-replace-epoch! frame-id incarnation-token
                                                 frame-state-before frame-state-after)
                true)))))))

(defn- perform-replace-frame-state!
  "Carry out the partial frame-state patch once preconditions have passed.
  `new-frame-state` is a PARTIAL frame-state map (any subset of
  `{:rf.db/app … :rf.db/runtime …}`); a present key replaces that
  partition, an absent key is carried forward unchanged from `frame-state-before`.
  The recorded after-state (`merge frame-state-before
  new-frame-state`) mirrors that same contract so the synthetic epoch's
  `:frame-state-after` matches exactly what the write installed.
  `incarnation-token` (from the preconditions) keys the whole transaction to
  the exact validated incarnation: the physical write goes through core's
  EXACT-INCARNATION `replace-frame-state!` 3-arity, which returns nil unless
  the token still names the live record — so a same-id successor can never
  receive the patch (rf2-gj2bo)."
  [frame-id incarnation-token new-frame-state]
  (perform-replace! frame-id
                    incarnation-token
                    (fn [frame-state-before]
                      (merge frame-state-before new-frame-state))
                    #(rf.frame/replace-frame-state! frame-id incarnation-token new-frame-state)))

(defn replace-frame-state!
  "Atomically install `new-frame-state` — a PARTIAL frame-state map (any
  subset of `{:rf.db/app … :rf.db/runtime …}`) — into `frame-id`'s
  frame-state, bypassing the dispatch loop: a present key replaces that
  partition, and an absent key is preserved. A db-shaped key never silently
  touches the other partition: app-only injection is
  `{:rf.db/app v}`; runtime-only is `{:rf.db/runtime v}`; both-partition
  install supplies both keys. Per Tool-Pair §Pair-tool writes.

  Records a synthetic `:rf/epoch-record` so `restore-epoch!` can rewind the
  previous state; emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on the frame-state and returns `false`,
  emitting a structured error trace):

    :rf.error/replace-frame-state-bad-keys — `new-frame-state` carries no
                                                   recognized partition key
                                                   (`:rf.db/app` /
                                                   `:rf.db/runtime`), or
                                                   carries an unrecognized
                                                   key — checked BEFORE frame
                                                   resolution (a caller-input-
                                                   shape error, not a frame
                                                   problem)
    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-during-drain       — drain in flight
    :rf.epoch/replace-schema-mismatch    — a PRESENT app-db partition fails
                                                   the frame's app-schema set,
                                                   OR a PRESENT runtime-db
                                                   partition fails the
                                                   framework-owned runtime-db
                                                   validator
    :rf.epoch/replace-history-disabled   — epoch ring disabled (depth 0);
                                                   the synthetic undo-anchor
                                                    cannot land

  Dev-only — gated on `interop/debug-enabled?`. Production builds elide.

  Returns `true` on success, `false` on any failure."
  [frame-id new-frame-state]
  (if-not rf.interop/debug-enabled?
    false
    (let [{:keys [outcome op tags incarnation-token]}
          (rf.epoch.tool-pair/check-replace-frame-state-preconditions! frame-id new-frame-state)]
      (case outcome
        ;; Carry the EXACT incarnation token the preconditions resolved against
        ;; to the write boundary, so a same-id successor seated in between never
        ;; receives this injection or its synthetic bookkeeping (rf2-gj2bo).
        :ok   (perform-replace-frame-state! frame-id incarnation-token new-frame-state)
        :fail (do (rf.epoch.tool-pair/emit-precondition-failure! op tags)
                  false)))))

;; ---- projected egress -----------------------------------------------------

(defn projected-record
  "Project an `:rf/epoch-record` for off-box egress.

  This is the required boundary for forwarding records across a process,
  logging, or tool wire. The in-process ring and listeners remain raw so
  restore and local inspection retain exact state.

  App-db-rooted slots are projected under the selected closed
  `:rf.egress/profile`. Sensitive values become `:rf/redacted`; large values
  become `:rf.size/large-elided` markers. Whole-frame slots project their
  app-db partition and redact runtime-db by default.

  Payloads that are not app-db-rooted fail closed independently:

    - trigger and trace-event arguments retain only the event id;
    - effect `:args` are redacted;
    - sub-run values respect sensitive and large classification;
    - unschematized HTTP bodies and resource-owned identities are redacted by
      their dedicated projectors.

  The configured `:redact-fn` runs once, after these built-in projections,
  and never mutates the raw record.

  The default profile is `:rf.egress/off-box-observability`. MCP and AI tools
  use `:rf.egress/off-box-tool`, which also includes structural marker
  digests. Advanced trusted-local overrides are `:include-sensitive?`,
  `:include-large?`, `:include-runtime-db?`, `:include-fx-args?`, and
  `:include-event-args?`; each lifts only its own boundary. The 1-arity uses
  safe defaults. Nil input returns nil."
  ([record] (rf.epoch.tool-pair/projected-record record))
  ([record opts] (rf.epoch.tool-pair/projected-record record opts)))

(defn projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv #(projected-record % opts) (epoch-history frame-id))`.
  The 2-arity threads egress `opts` to every record; the 1-arity uses the
  safe off-box defaults."
  ([frame-id] (rf.epoch.tool-pair/projected-history frame-id))
  ([frame-id opts] (rf.epoch.tool-pair/projected-history frame-id opts)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; The router calls `settle!` after each dequeued event, and trace emission
;; calls `capture-event!`. Late binding keeps core independent of this optional
;; artefact. Most absent-artefact reads degrade to empty/false/no-op; the state
;; replacement facade raises because it cannot preserve its undo invariant
;; without epoch storage.
(rf.late-bind/set-fns!
  {;; ---- per-cascade lifecycle (router + trace capture seam) -------
   :epoch/settle!             settle!
   ;; Per-event halt commit for a depth-exceeded event that never ran, so
   ;; `settle!` would otherwise skip its empty buffer.
   :epoch/commit-halt-record! commit-halt-record!
   :epoch/capture-event       rf.epoch.capture/capture-event!
   ;; In-flight cause lookup used to attribute render traces.
   :epoch/run-cause           rf.epoch.capture/run-cause
   ;; Post-settle render back-fill. `capture-event!` routes a
   ;; view-render op that fires with no in-flight cascade (a React-
   ;; commit-time async re-render) here so it is attributed to the
   ;; cascade that CAUSED it (the frame's most-recently-settled epoch)
   ;; rather than the next in-flight cascade. The orchestrator lives in
   ;; `re-frame.epoch.listeners` (state back-fill + listener re-notify);
   ;; publishing it through late-bind keeps `capture` free of a require
   ;; on `listeners` (which would close the assembly→capture cycle).
   :epoch/record-render!      rf.epoch.listeners/record-render!
   ;; Post-settle sub-run back-fill, parallel to render back-fill for the
   ;; same React-deref-time attribution case.
   :epoch/record-sub-run!     rf.epoch.listeners/record-sub-run!
   ;; Post-settle view-unmount back-fill, the teardown sibling
   ;; of the two above. A `:rf.view/unmounted` fires at React teardown time,
   ;; after the cascade that removed the view settled — too late for the
   ;; capture seam. Back-fills it into the causing (most-recently-settled)
   ;; epoch's `:trace-events`, where Xray's VIEWS step surfaces it.
   :epoch/record-unmount!     rf.epoch.listeners/record-unmount!
   ;; rf2-vxgfnd.151: `destroy-frame!` fires this BEFORE dissoc to bind the
   ;; dying incarnation's terminal halted-destroy evidence to a bundle while it
   ;; still solely owns its id-keyed epoch stores; the bundle is threaded to the
   ;; post-dissoc `:epoch/on-frame-destroyed` hook.
   :epoch/snapshot-frame-destroyed rf.epoch.listeners/snapshot-terminal-destroy-evidence!
   :epoch/on-frame-destroyed  rf.epoch.listeners/on-frame-destroyed!

   ;; ---- introspection + Tool-Pair write surface --------------------
   :epoch/epoch-history       epoch-history
   :epoch/restore-epoch!      restore-epoch!
   :epoch/replay-epoch!       replay-epoch!
   :epoch/replace-frame-state! replace-frame-state!

   ;; ---- listener + config surface ----------------------------------
   :epoch/register-epoch-listener!   register-epoch-listener!
   :epoch/unregister-epoch-listener! unregister-epoch-listener!
   ;; The ONE supported receiver decision for a
   ;; `:rf.epoch.cb/silenced-on-frame-destroy` signal (rf2-uhouu, superseding the
   ;; rf2-6ys5n generation query + rf2-qg98y observing query it composes): does
   ;; this silence still name a current fact? Registration identity AND
   ;; observation continuum are weighed under one ledger snapshot, because the
   ;; two-query composite a consumer would otherwise write is not linearizable.
   :epoch/epoch-silence-current?     epoch-silence-current?
   :epoch/configure!                 configure!
   ;; Test-support config-isolation hook. `re-frame.test-
   ;; support`'s reset-hook table fires this to restore epoch config to
   ;; the shipped default between tests, keeping the private `state/config`
   ;; var out of test namespaces.
   :epoch/reset-config!              rf.epoch.state/reset-config!
   :epoch/clear-history!             clear-history!
   :epoch/clear-epoch-listeners!     clear-epoch-listeners!

   ;; ---- off-box egress projection ---------------------------------
   :epoch/projected-record    projected-record
   :epoch/projected-history   projected-history})
