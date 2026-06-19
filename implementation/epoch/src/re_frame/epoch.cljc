(ns re-frame.epoch
  "Per-frame epoch history. Per Tool-Pair §Time-travel and Spec-Schemas
  §`:rf/epoch-record`.

  Every DEQUEUED EVENT's run-to-completion marks an epoch boundary — one
  `:rf/epoch-record` per event, NOT per drain (per Spec 002 §Drain versus
  event, rf2-u6jsj/rf2-nj6p7). A drain that processes a parent event and
  the `:fx [[:dispatch …]]` child it queued commits TWO records. A machine
  macrostep (`:raise` / `:always` microsteps) runs inside one event and
  stays ONE epoch. The runtime records, per frame, an `:rf/epoch-record`
  with:

    :epoch-id       opaque, unique within a frame's history
    :frame          frame keyword
    :committed-at   the committing event's CAUSAL time — its envelope's
                    `:rf.cofx` `:rf/time-ms`, stamped at the router's
                    causal boundary (rf2-bh56rc / EP-0010 §Time), NOT an
                    ambient assembly-time clock read; replayable
    :event-id       the event keyword that triggered the cascade
    :trigger-event  the full event vector
    :db-before      app-db snapshot before the cascade
    :db-after       app-db snapshot after the drain settled
    :trace-events   the raw trace stream that produced this epoch
    :sub-runs       structured projection of subscription activity
    :renders        structured projection of render activity
    :effects        structured projection of fx-walk activity

  Records are kept in a per-frame ring buffer (default depth 50,
  configurable via `(rf/configure! {:epoch-history {:depth N}})`). Older
  records are evicted when the buffer is full.

  The entire epoch-history machinery is gated on `interop/debug-enabled?`,
  the same compile-time goog-define as the trace surface. Production
  builds elide; no allocation, no storage, no overhead.

  Listener API (`register-epoch-listener!` / `unregister-epoch-listener!`) mirrors the
  raw-trace listener API in `re-frame.trace`. Listeners receive the
  fully-assembled record after it lands in the ring buffer.

  Restore (`restore-epoch!`) rewinds a frame to the named epoch's
  canonical `:frame-state-after` — the WHOLE frame-state, with app-db
  and runtime-db as two separate partitions reinstalled in ONE atomic
  write (EP-0001 rf2-3aizt1; Tool-Pair §Time-travel). The retained
  `:db-before` / `:db-after` are OPTIONAL app-db projections of that
  frame-state, not the restore target itself. Seven documented failure
  modes (Tool-Pair §Time-travel restore-failure-modes table) each emit
  a structured trace and leave the frame's frame-state unchanged — six
  under the reserved `:rf.epoch/*` namespace, plus the registry-lookup
  `:rf.error/no-such-handler` (kind `:frame`) for an unknown frame-id."
  (:require [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.listeners :as listeners]
            [re-frame.epoch.state :as state]
            [re-frame.epoch.tool-pair :as tool-pair]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

;; ---- configuration --------------------------------------------------------
;;
;; Atoms, defaults, and config-merge validation live in `re-frame.epoch.state`.
;; The facade keeps the public docstrings and the late-bind hook publication.
;;
;; Design provenance (kept out of the public `configure!` docstring per the
;; rf2-ee38b clarity review):
;;   * `:trace-events-keep` defaults to a FINITE value equal to `:depth`
;;     (50 — see `re-frame.epoch.state/default-trace-events-keep`; rf2-mrsck /
;;     Security.md §Epoch privacy posture). With the default the most-recent
;;     `:depth` records per frame retain raw `:trace-events`; when an epoch
;;     evicts from the ring its raw trace evicts with it, so trace + epoch
;;     stay atomic (Mike pair-debug 2026-05-27 — the prior finite-5 default
;;     created the discrepancy of 50 retained records but only 5 with their
;;     `:trace-events`). Older records (beyond the keep-window, when an app
;;     configures a keep < depth) keep only the cheap structured projections.
;;     Memory-conscious hosts pass a smaller value (e.g.
;;     `{:trace-events-keep 5}`); `0` drops every record's `:trace-events`.
;;     `(rf/configure! {:epoch-history {:trace-events-keep nil}})` is a no-op
;;     against the explicit-value validation; use a numeric value or omit the
;;     slot.
;;   * Keys are validated at the boundary (refactor-audit r2 rf2-lwn4t
;;     §rf2-douii): a `:depth` / `:trace-events-keep` that isn't a
;;     non-negative integer is silently dropped rather than stored, so a
;;     nil / non-numeric value can't survive into `record!` and explode at
;;     the next `pos?` / `nat-int?`. Mirrors `re-frame.trace/configure-trace-
;;     buffer!`'s own config-boundary validation.

(defn configure!
  "Update the epoch-history configuration. Supported keys:

    :depth              N — non-negative integer; ring-buffer depth per
                        frame. 0 disables recording (assembled records can
                        still fire on listeners but nothing lands in the
                        ring buffer).
    :trace-events-keep  N — non-negative integer; cap how many of the
                        MOST-RECENT records per frame retain their raw
                        `:trace-events` vector. Older records keep the cheap
                        structured projections (`:sub-runs` / `:renders` /
                        `:effects`) but drop `:trace-events` to bound memory.
                        Defaults to the `:depth` value (50) so trace + epoch
                        evict atomically; pass a smaller value (e.g. 5) to
                        bound dev-session heap more aggressively.
    :redact-fn          fn? or nil. The ADVANCED PROJECTION-SIDE override
                        (EP-0015 §15 + open-issue 6, RULED). When non-nil the
                        framework invokes the fn ONCE per record at the
                        OFF-BOX EGRESS boundary — inside `projected-record`,
                        AFTER the frame/profile `project-egress` projection —
                        NOT at storage time. The ring buffer and every
                        `register-epoch-listener!` listener receive the RAW
                        record: post-EP-0010 epoch records are causal replay
                        material, and mutating them at rest would corrupt the
                        replay contract. The fn is the rare advanced escape
                        for an app that records material the declaration-driven
                        projection cannot prove (a sensitive slot no frame /
                        schema declaration covers); ordinary redaction needs
                        only the frame's `:sensitive` / `:large` classification
                        (EP-0015 §3) plus the per-slot schema props machine /
                        resource data carry, which `projected-record` already
                        applies. A throwing
                        fn emits `:rf.warning/epoch-redact-fn-exception` and
                        falls back to the projected (frame/profile-redacted)
                        record. Passing `nil` clears any previously-installed
                        fn. CAVEAT: the fn runs only on the projected egress
                        copy; it cannot affect `restore-epoch!` fidelity (the
                        ring stays raw) — that hazard is gone by construction.

  Invalid `:depth` / `:trace-events-keep` (not a non-negative integer) and
  malformed `:redact-fn` (not `fn?` / `nil`) are silently dropped at the
  boundary. See EP-0015 §15 (Epoch Redaction) + open-issue 6 disposition,
  Tool-Pair §Time-travel §Redaction hook + Security.md §Epoch privacy posture
  for the full contract."
  [opts]
  (state/merge-config! opts))

(defn current-config
  "Return the current epoch-history configuration map. Public for tests
  and tools that want to display the current depth."
  []
  (state/current-config))

;; The test-support config-isolation seam (`:epoch/reset-config!`,
;; rf2-yw1w1u) points straight at `state/reset-config!` from the late-bind
;; map below (rf2-c0rv4v — no facade wrapper; `state/reset-config!`'s
;; docstring is the canonical pin and there is no distinct public docstring
;; to keep here).

;; The record-assembly helpers — `current-schema-digest`, the
;; sensitive-rollup family, and `build-record` itself — live in
;; `re-frame.epoch.assembly`. The `:redact-fn` advanced override
;; (`apply-redact-fn`) is projection-side only (EP-0015 §15 + open-issue
;; 6, RULED) — invoked from `projected-record`, never at storage time.

;; ---- the per-frame ring buffer --------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history": last N epochs per frame.
;; Stored as a map of frame-id → vector (oldest-first). New records append
;; to the back; the front evicts when the buffer exceeds the configured
;; depth. The atom + ring-buffer mutators live in `re-frame.epoch.state`.

(defn epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs (or when
  depth is 0, which disables recording)."
  [frame-id]
  (state/history-for frame-id))

(defn clear-history!
  "Drop every recorded epoch for every frame. Test fixtures use this.

  Per rf2-v0jwt: also drops any in-flight per-frame capture buffer.
  Conformance / unit-test fixtures that sequence runs need a fresh
  capture state per fixture so the halted-cascade record commits
  observe THIS fixture's drain only — a buffer left over from a
  previous fixture's mid-flight emit (e.g. a `:frame/created` event
  whose drain didn't fire `harvest-buffer!`) would otherwise be
  picked up by the next fixture's first cascade."
  []
  (state/reset-histories!)
  (state/reset-capture-buffers!)
  nil)

;; ---- listener registry ----------------------------------------------------
;;
;; The listener / observed-frames atoms and their low-level CRUD live in
;; `re-frame.epoch.state`; the facade keeps the public docstrings and the
;; fan-out / failure-isolation policy.

(defn register-epoch-listener!
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. The id can be any comparable value; passing the
  same id twice replaces. Per Spec 009 §`register-epoch-listener!` —
  assembled-epoch listener.

  The callback receives a fully-formed record with `:db-after`,
  `:sub-runs`, `:renders`, `:effects`, and `:trace-events` populated.
  The record has already been appended to the frame's `epoch-history`
  ring buffer when the callback runs.

  Listener exceptions are caught and isolated; one broken listener
  cannot break the runtime or block other listeners.

  Returns the id."
  [id f]
  (state/put-listener! id f))

(defn unregister-epoch-listener!
  "Remove the listener registered under id."
  [id]
  (state/drop-listener! id))

(defn clear-epoch-listeners!
  []
  (state/reset-listeners!))

;; `notify-listeners!` (the fan-out + failure-isolation policy) and
;; `on-frame-destroyed!` (the four-step destroy contract that
;; straddles state, capture, and assembly) live in
;; `re-frame.epoch.listeners` (Phase-2 seam C, rf2-0wi86). The
;; `:epoch/on-frame-destroyed` late-bind slot points straight at
;; `listeners/on-frame-destroyed!` (rf2-c0rv4v — no facade wrapper; that
;; seam carries no public docstring distinct from the listeners pin).

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
;; The atom + buffer-CRUD live in `re-frame.epoch.state` (Phase-2
;; seam A, rf2-0wi86).

;; The skip-ops catalogue, the late-bind `:epoch/capture-event` entry
;; point, and the two read-only walks (`project-all`,
;; `find-trigger-event`) live in `re-frame.epoch.capture` (Phase-2
;; seam B, rf2-0wi86).

;; ---- per-event settle hook ------------------------------------------------

(defn- commit-record!
  "Build, ring-append, and fan out one RAW `:rf/epoch-record`. Shared
  by the per-event clean settle (`settle!`) and the per-event halt commit
  (`commit-halt-record!`). `events` is the harvested cascade buffer (may
  be empty for a halt whose event never ran); `trigger-event` is an
  explicit `[event-id …]` vector to pin the record's trigger when the
  buffer carries no `:event/run-start` (the depth-exceed halting event,
  per rf2-nj6p7), or nil to let `build-record` derive the trigger from
  the buffer (the normal `:ok` path).

  Per EP-0015 §15 + open-issue 6 (RULED, hardened): the ring buffer and
  every `register-epoch-listener!` listener receive the RAW record.
  Storage-side redaction was REMOVED — post-EP-0010 epoch records are
  causal replay material, and mutating them at rest corrupts the replay
  contract (not merely restore fidelity). The app-supplied `:redact-fn`
  is now a PROJECTION-SIDE-ONLY advanced override, applied at the off-box
  egress boundary inside `projected-record` (never at storage time). The
  `:rf.epoch/sensitive?` rollup inside `build-record` still reflects raw
  signals so off-box consumers can branch on it before projecting.

  `committed-at` is the record's durable causal time — per EP-0010 §Time
  and Spec 002 §The World-Input Rule (rf2-bh56rc) the committing causal
  token's `:rf.cofx` `:rf/time-ms`, threaded down from the router's
  per-event settle / depth-halt seam, NOT an ambient host-clock read at
  assembly time. This makes `:committed-at` replayable."
  [frame-id frame-state-before frame-state-after events committed-at outcome halt-reason trigger-event]
  ;; EP-0001 (rf2-3aizt1, decision #2): the canonical snapshot unit is the
  ;; whole frame-state (both partitions); `build-record` stores it as
  ;; `:frame-state-before` / `:frame-state-after` and derives the
  ;; `:db-before` / `:db-after` app-db projections.
  (let [base   (assembly/build-record frame-id frame-state-before frame-state-after
                                      events committed-at outcome halt-reason)
        ;; Pin the explicit trigger when supplied AND the buffer didn't
        ;; already resolve one (the halting event never ran, so no
        ;; `:event/run-start` was buffered). Per Spec-Schemas
        ;; §:rf/epoch-record the slots are :keyword / vector — never nil.
        record (cond-> base
                 (and trigger-event (not (:event-id base)))
                 (assoc :event-id      (first trigger-event)
                        :trigger-event trigger-event))]
    (state/record! record)
    ;; Per rf2-qs6dl: mark this as the frame's most-recently-settled
    ;; epoch so post-settle async render emits (which fire at React
    ;; commit time, after this event settled) are attributed back to
    ;; THIS event rather than buffered into the next one. Set after
    ;; `record!` so the record is in the ring before any render can
    ;; back-fill into it.
    (state/set-last-settled-epoch! frame-id (:epoch-id record))
    ;; Per rf2-931pm — focused-event-only cascade-DAG capture. Sticky
    ;; hook (rf2-f72pd) published by `re-frame.trace.cascade` at ns-load;
    ;; when the trace.cascade ns has not been loaded (e.g. tooling-
    ;; stripped JVM consumers) the lookup returns nil and the call
    ;; short-circuits. The aggregator's own focus-predicate gate decides
    ;; whether to emit `:rf.cascade/captured` — off-focus epochs pay just
    ;; the predicate call (default predicate returns false).
    (when-let [capture (late-bind/get-fn-cached
                         :trace.cascade/capture-for-epoch!)]
      (try
        (capture frame-id (:epoch-id record) (:event-id record) events)
        (catch #?(:clj Throwable :cljs :default) _ nil)))
    ;; Per rf2-18g1w / rf2-jppad — the cascade-trailer pair: the detailed
    ;; `:rf.epoch/snapshotted` (tools that want the CAUSE read its
    ;; `:outcome` tag) plus the consumer-facing `:rf.epoch/outcome`
    ;; (`{:ok :blocked :error}` — the Xray Trace panel close-row §13 and
    ;; Story outcome chips read this op's tag directly). The shared
    ;; `emit-snapshotted+outcome!` keeps the trailer shape identical to
    ;; the mid-drain destroy commit's.
    (assembly/emit-snapshotted+outcome! frame-id (:epoch-id record)
                                        (:event-id record) outcome)
    (listeners/notify-listeners! record)
    record))

(defn settle!
  "Hook called by the router once per DEQUEUED EVENT — at each event's
  run-to-completion boundary, NOT once per drain. Per Spec 002
  §Drain versus event — the epoch unit (rf2-u6jsj/rf2-nj6p7) and
  Tool-Pair §Time-travel and rf2-v0jwt §Outcomes.

  Per rf2-nj6p7: the epoch boundary is the dequeued event, not the
  drain-settle. A drain that processes a parent event and an
  `:fx [[:dispatch …]]` child it queued therefore produces TWO records
  (one per event), each with its OWN `:db-before` / `:db-after` snapshot
  pair and its own harvested trace buffer. The router calls this after
  each `process-event!` returns, so the capture buffer it harvests holds
  exactly that one event's six-domino cascade (within a frame, execution
  is single-threaded run-to-completion, so the buffer is cleanly the
  in-flight event's). A machine macrostep — `:raise` sub-events and
  `:always` microsteps — runs INSIDE a single `process-event!`, so its
  emits ride the triggering event's buffer and settle as ONE epoch (per
  Spec 005 §macrostep); they do not allocate a new epoch.

  `committed-at` is the record's durable causal time — per EP-0010 §Time
  (epoch record causal time) and Spec 002 §The World-Input Rule
  (rf2-bh56rc) the committing causal token's `:rf.cofx` `:rf/time-ms`,
  read ONCE at the causal boundary (envelope construction) and threaded
  down here by the router's per-event settle seam (`settle-event-epoch!`),
  NOT an ambient host-clock read at assembly time. This makes the record's
  `:committed-at` replayable: the same event log replayed with the same
  supplied `:rf/time-ms` values yields records with equal `:committed-at`.

  Arities:
    (settle! frame-id frame-state-before frame-state-after committed-at)
      Clean per-event settle. `:outcome` is `:ok`. Equivalent to passing
      `:ok` as `outcome` explicitly. Skips recording when the captured
      buffer is empty (a truly empty cascade — likely a rejected
      dispatch — is degenerate and would emit a misleading record).
    (settle! frame-id frame-state-before frame-state-after committed-at outcome halt-reason)
      Drain-boundary commit with explicit outcome. The runtime commits
      one of three outcomes: `:ok` / `:halted-depth` / `:halted-destroy`
      (`:halted-handler-exception` is a schema-reserved value the
      reference runtime never emits — handler exceptions ride the
      interceptor error-capture seam and the drain settles `:ok` with the
      error trace under `:trace-events`; see Spec-Schemas §`:rf/epoch-record`
      §Outcomes and Spec 009 §register-epoch-listener!). `halt-reason` is
      a structured descriptor populated on halt paths (nil on `:ok`). On a buffer
      with no recoverable trigger (no `:event/run-start` and no
      `:event-id` tag — e.g. a destroy that races a registration-time
      emit) `build-record` omits `:event-id` / `:trigger-event`
      entirely; the schema admits absent slots, rejects nil values
      (per rf2-kl5p1 / audit r3 §F1).

  `frame-state-before` is the whole frame-state value (both partitions)
  snapshotted before the cascade began; `frame-state-after` is the value
  the runtime settled to — equal to `frame-state-before` for atomic-rollback
  halts (`:halted-depth`), the live frame-state for the destroy path
  (`:halted-destroy`), the post-drain value for `:ok`. EP-0001 (rf2-3aizt1,
  decision #2): the canonical snapshot unit is the whole frame-state;
  `build-record` derives the `:db-before` / `:db-after` app-db projections
  from it. The captured trace buffer is harvested here and projected into
  the record.

  Emits `:rf.epoch/snapshotted` with a `:outcome` tag so trace listeners
  can discriminate clean from halted boundaries without inspecting the
  epoch-history vector. Listeners (`register-epoch-listener!`) receive every
  record regardless of outcome.

  See also `commit-halt-record!` — the sibling commit path for the
  depth-exceed halt whose halting event never ran, so the buffer is empty
  at halt and `settle!`'s empty-buffer skip would suppress the record.
  Both fns share the private `commit-record!` helper; `commit-halt-record!`
  synthesises the halting event's trigger explicitly while `settle!` lets
  `build-record` derive it from the harvested buffer."
  ([frame-id frame-state-before frame-state-after committed-at]
   (settle! frame-id frame-state-before frame-state-after committed-at :ok nil))
  ([frame-id frame-state-before frame-state-after committed-at outcome halt-reason]
   (when interop/debug-enabled?
     ;; Per rf2-nj6p7: scoped harvest — take only the settling event's
     ;; traces (its `:dispatch-id` + pre-cascade tagalongs), LEAVING any
     ;; child's `:event/dispatched` marker (emitted during THIS event's
     ;; do-fx, carrying the child's id) in the buffer for the child's own
     ;; settle. Keeps each epoch's `:trace-events` to one `:dispatch-id`
     ;; (Spec 009 §Dispatch correlation: one dispatch-id = one epoch).
     (let [events (state/harvest-buffer-for-event! frame-id)]
       ;; Empty-buffer policy (consistent across outcomes): an empty
       ;; capture buffer means no cascade context was recorded for
       ;; this event — skip emission rather than commit a record with
       ;; no :event-id / :trigger-event. A rejected/aborted dispatch
       ;; (no `:event/run-start` ever fired) reaches this seam with an
       ;; empty buffer and is correctly suppressed. Halt paths whose
       ;; halting event never ran (the per-event depth-exceed boundary,
       ;; per rf2-nj6p7) use `commit-halt-record!` instead, which
       ;; synthesises the halting event's trigger explicitly.
       (when (seq events)
         (commit-record! frame-id frame-state-before frame-state-after events
                         committed-at outcome halt-reason nil))))))

(defn- commit-halt-record!
  "Commit a `:halted-*` epoch record for a drain halt whose halting event
  never ran to completion — the per-event depth-exceed boundary
  (rf2-nj6p7). Unlike `settle!`, this does NOT skip on an empty capture
  buffer: under per-event epochs the events that ALREADY ran each
  harvested their own buffer and committed their own `:ok` epoch, so the
  buffer is empty when the depth limit trips. The halting event (the next
  one that would have been dequeued) never ran, so it has no cascade
  trace; this seam synthesises its `:halted-depth` record from the
  explicit `trigger-event` so devtools (Xray, re-frame2-pair) get a
  clear 'drain halted here' marker following the runaway `:ok` epochs.

  `frame-state-before` / `frame-state-after` are equal — the halting event
  made no write (it never ran). Per rf2-nj6p7 the already-settled sibling events are
  DURABLE (their `:ok` epochs and db writes survive); there is no
  whole-drain rollback under per-event epochs — see the router's
  `handle-depth-exceeded!` and the report note on the rule-3 reconcile.

  Harvests-and-clears any residual buffer first so a stray pre-halt emit
  (there should be none under per-event settling) can't leak into the
  next cascade for this frame.

  `committed-at` is the record's durable causal time — per EP-0010 §Time
  and Spec 002 §The World-Input Rule (rf2-bh56rc) the halting causal
  token's `:rf.cofx` `:rf/time-ms`, threaded down from the router's
  `handle-depth-exceeded!` seam, NOT an ambient host-clock read. The
  halting event never ran, but its envelope carries a `:time-ms` stamped
  at its dispatch (the causal boundary); using it keeps even this
  synthesised `:halted-depth` marker replayable.

  See also `settle!` — the clean-path sibling that handles every per-
  event `:ok` settle. Both fns share the private `commit-record!`
  helper; `settle!` lets `build-record` derive the trigger from the
  harvested buffer (an `:event/run-start` is always present on the
  clean path) and skips on an empty buffer."
  [frame-id frame-state-before frame-state-after committed-at outcome halt-reason trigger-event]
  (when interop/debug-enabled?
    (let [events (state/harvest-buffer! frame-id)]
      (commit-record! frame-id frame-state-before frame-state-after events
                      committed-at outcome halt-reason trigger-event))))

;; ---- restore --------------------------------------------------------------
;;
;; Precondition validators, schema/handler/version probes, and
;; `perform-restore!` live in `re-frame.epoch.tool-pair` (Phase-2 seam E,
;; rf2-0wi86). The orchestrator below stays in the facade — it wires
;; the precondition check + the trace emission + the perform step into
;; a four-line case-match.

(defn restore-epoch!
  "Rewind the frame to the named epoch's canonical `:frame-state-after`
  — the WHOLE frame-state, reinstalling app-db AND runtime-db as two
  separate partitions in ONE atomic write (EP-0001 rf2-3aizt1: reviving
  machine snapshots, the route slice, elision declarations, and SSR
  metadata, not just the app-db partition). `:frame-state-after` is the
  only restore source — every record `build-record` emits carries it; the
  retained `:db-after` is an OPTIONAL app-db projection for tool diffs,
  never a restore source. Emits `:rf.epoch/restored` on success.

  Failure modes (each is a no-op on the frame-state and emits a
  structured error trace):

    :rf.error/no-such-handler          (kind :frame) — frame not registered
    :rf.epoch/restore-during-drain     — called while drain is in flight
    :rf.epoch/restore-unknown-epoch    — epoch-id not in current history
    :rf.epoch/restore-non-ok-record    — target epoch's :outcome is not :ok
                                         (per rf2-v0jwt — halted-cascade
                                         records carry partial state and
                                         are not valid restore targets)
    :rf.epoch/restore-schema-mismatch  — db-after no longer validates
    :rf.epoch/restore-missing-handler  — referenced registration absent
    :rf.epoch/restore-version-mismatch — machine snapshot version drift

  Returns `true` on success, `false` on any failure."
  [frame-id epoch-id]
  (if-not interop/debug-enabled?
    false
    (let [{:keys [outcome epoch op tags]} (tool-pair/check-restore-preconditions! frame-id epoch-id)]
      (case outcome
        :ok   (tool-pair/perform-restore! frame-id epoch)
        :fail (do (tool-pair/emit-precondition-failure! op tags)
                  false)))))

;; ---- replace-app-db! / reset-app-db! (Tool-Pair §Pair-tool writes) -------
;;
;; Per Tool-Pair §Pair-tool writes: a public Tool-Pair write surface that
;; replaces a frame's `app-db` PARTITION with an arbitrary new value,
;; bypassing the dispatch loop. Used by pair-shaped tools for state
;; injection (evolved-state-shape probes after a handler hot-swap), story
;; tools, conformance harnesses, and time-travel from JSON-loaded bug
;; repros. The runtime-db partition is never touched (Mike ruling #10 — a
;; db-shaped name never silently replaces runtime-db).
;;
;; EP-0001 (rf2-tfepxu, bead 9): the surface formerly named `reset-frame-db!`
;; is renamed to `replace-app-db!` (Mike ruling #10 + spec/API.md), with a
;; new app-db-only sibling `reset-app-db!` that resets the app-db partition
;; to `{}` while preserving live runtime-db — the app-db sibling of the
;; whole-frame `reset-frame!`.
;;
;; The surface is dev-only — gated on `interop/debug-enabled?`, the same
;; gate as `restore-epoch!` / `register-epoch-listener!` / the rest of the
;; epoch-history machinery. Production builds (`:advanced` +
;; goog.DEBUG=false) elide the body via Closure DCE; the surface is not
;; available in shipped binaries.
;;
;; Failure modes (each is a no-op on `app-db` and returns `false`):
;;   :rf.error/no-such-handler              (kind :frame) — frame not registered
;;   :rf.epoch/replace-during-drain  — called while drain is in flight
;;   :rf.epoch/replace-schema-mismatch — `new-db` fails the frame's
;;                                              registered app-schema set
;;
;; On success: records a synthetic `:rf/epoch-record` (so undo via
;; `restore-epoch!` works against the previous state), emits
;; `:rf.epoch/db-replaced`, replaces the container, and fires registered
;; epoch listeners with the assembled record.

(defn- record-synthetic-replace-epoch!
  "Record a synthetic `:rf.epoch/db-replaced` epoch for a pair-tool
  injection and fan it out. Shared by all three partition-replace perform
  helpers (`perform-replace-app-db!`, `perform-replace-runtime-db!`,
  `perform-replace-frame-state!`) — the single lockstep-maintained site for
  the synthetic-record / committed-at / redaction contract. `fs-before` /
  `fs-after` are the pre- and post-replace coherent frame-state values;
  restore of this synthetic record reinstalls `fs-after`, and restore of a
  PRIOR epoch rewinds past the injection.

  Per EP-0015 §15 + open-issue 6 (RULED): the synthetic record is stored
  RAW — storage-side redaction was removed (the ring is causal replay
  material); the `:redact-fn` advanced override runs projection-side only,
  inside `projected-record`. Per rf2-qs6dl: stamps the synthetic epoch as
  the frame's last-settled so post-settle re-renders attribute back to it
  rather than the next real cascade.

  rf2-bh56rc: a pair-tool injection — no application event / causal token
  is in flight, so `:committed-at` is the tool action's own wall-clock; the
  ambient read happens HERE rather than inside the pure `build-record`
  builder (per EP-0010 §Time — ambient time stays allowed for a tool
  action's wall-clock).

  rf2-czwwf4: `:committed-at` is a DURABLE epoch field, so the ambient read
  uses `interop/epoch-now-ms` (wall-clock epoch ms, `js/Date.now()` on
  CLJS) — NOT `interop/now-ms`, which on CLJS is `performance.now()`
  (origin-relative, NOT for durable facts). This keeps a tool-injected
  epoch's `:committed-at` in the same wall-clock CLASS as the
  router-stamped epochs (EP-0010 §Time durable-timestamp rule)."
  [frame-id fs-before fs-after]
  (let [record (assoc (assembly/build-record frame-id fs-before fs-after []
                                             (interop/epoch-now-ms))
                      :event-id      :rf.epoch/db-replaced
                      :trigger-event [:rf.epoch/db-replaced])]
    (state/record! record)
    (state/set-last-settled-epoch! frame-id (:epoch-id record))
    (trace/emit! :rf.epoch :rf.epoch/db-replaced
                 {:frame       frame-id
                  :rf.epoch/id (:epoch-id record)})
    (listeners/notify-listeners! record)
    record))

(defn- perform-replace!
  "Carry out a partition-replace once preconditions have passed — the
  shared body behind `perform-replace-app-db!` / `perform-replace-runtime-db!`
  / `perform-replace-frame-state!` (rf2-c0rv4v). `write-fn` is a 0-arg thunk
  that performs the `frame/replace-*!` partition write (the wrapper binds the
  frame-id + new value into it); `fs-after-fn` builds the coherent
  post-replace frame-state from the pre-replace `fs-before` (so restore of the
  synthetic epoch reinstalls the right two-partition value). Returns `true`
  on a real write.

  Per rf2-7i872 (validate-then-destroy race): re-resolves the container at
  the write boundary via `tool-pair/live-container-or-fail`. If the frame was
  destroyed between the precondition pass and now, the container is nil and
  `replace-container!` would silently no-op — so instead of recording a
  synthetic epoch for a destroyed frame, fanning it out to listeners,
  emitting `:rf.epoch/db-replaced`, and returning `true` (a FALSE success
  against a frame that no longer exists), this emits the canonical
  `:rf.error/no-such-handler` (kind `:frame`) failure trace and returns
  `false`, matching the destroyed-frame contract.

  Per rf2-s93722 (post-liveness teardown race): the liveness check closes
  only HALF the window — a frame destroyed AFTER `live-container-or-fail`
  passes but BEFORE the `frame/replace-*!` write actually lands still slips
  through. The partition writers return `nil` for a destroyed frame (a
  non-nil — possibly EMPTY — changed-key-set for a live frame, even a no-op
  write), so we check that return: a `nil` return is the destroyed-frame
  signal, surfaced as the same `:rf.error/no-such-handler` (kind `:frame`)
  failure / `false` return BEFORE any synthetic epoch, listener fanout, or
  success telemetry. On a real write records a synthetic epoch via
  `record-synthetic-replace-epoch!` (single lockstep-maintained site for the
  synthetic-record / committed-at / redaction contract — see that fn's
  docstring for the rf2-bh56rc / rf2-czwwf4 / qs6dl rationale).

  EP-0001 (rf2-adwcv6): the partition writes go through the `frame/replace-*!`
  writers because `frame/app-db-container` / `runtime-db-container` are now
  READ-ONLY projections, so a direct `replace-container!` on them throws."
  [frame-id fs-after-fn write-fn]
  (let [{:keys [outcome op tags]} (tool-pair/live-container-or-fail frame-id)]
    (if (= :fail outcome)
      (do (tool-pair/emit-precondition-failure! op tags)
          false)
      (let [;; EP-0001 (rf2-3aizt1): the canonical snapshot unit is the whole
            ;; frame-state; `fs-after-fn` builds the coherent post-replace
            ;; value (app-db-only / runtime-db-only / both-partition per the
            ;; calling wrapper). Restore of the synthetic epoch reinstalls it.
            ;; rf2-s93722: capture the write return — `nil` means the frame was
            ;; destroyed in the post-liveness window (no write happened); a
            ;; non-nil changed-key-set (even empty) means the write landed.
            fs-before (frame/frame-state-value frame-id)
            fs-after  (fs-after-fn fs-before)
            changed   (write-fn)]
        (if (nil? changed)
          (do (tool-pair/emit-precondition-failure! :rf.error/no-such-handler
                                                    {:kind :frame :frame frame-id})
              false)
          (do (record-synthetic-replace-epoch! frame-id fs-before fs-after)
              true))))))

(defn- perform-replace-app-db!
  "Carry out the `app-db` replacement once preconditions have passed.
  Replaces ONLY the app-db partition (Mike ruling #10 — a db-shaped name
  never silently touches runtime-db); the after-frame-state carries the new
  app-db with the live runtime-db preserved unchanged. Partition-binding
  wrapper over `perform-replace!` (rf2-c0rv4v) — see that fn for the full
  validate-then-destroy / post-liveness-teardown contract."
  [frame-id new-db]
  (perform-replace! frame-id
                    #(assoc % frame/app-partition-key new-db)
                    #(frame/replace-app-db! frame-id new-db)))

(defn- perform-replace-runtime-db!
  "Carry out the runtime-db replacement once preconditions have passed —
  the runtime-db sibling of `perform-replace-app-db!`. Replaces ONLY the
  runtime-db partition (app-db preserved unchanged). Partition-binding
  wrapper over `perform-replace!` (rf2-c0rv4v)."
  [frame-id new-runtime-db]
  (perform-replace! frame-id
                    #(assoc % frame/runtime-partition-key new-runtime-db)
                    #(frame/replace-runtime-db! frame-id new-runtime-db)))

(defn- perform-replace-frame-state!
  "Carry out the full-frame (both-partition) replacement once preconditions
  have passed — the whole-frame sibling of `perform-replace-app-db!`.
  Replaces BOTH partitions atomically (`{:rf.db/app … :rf.db/runtime …}`).
  A missing partition key installs `nil` for that partition (a full-frame
  replace is whole-value by contract — see `frame/replace-frame-state!`);
  the recorded after-state is normalised to the same coherent shape.
  Partition-binding wrapper over `perform-replace!` (rf2-c0rv4v)."
  [frame-id new-frame-state]
  (perform-replace! frame-id
                    (fn [_fs-before]
                      {frame/app-partition-key     (get new-frame-state frame/app-partition-key)
                       frame/runtime-partition-key (get new-frame-state frame/runtime-partition-key)})
                    #(frame/replace-frame-state! frame-id new-frame-state)))

(defn replace-app-db!
  "Replace `frame-id`'s `app-db` partition with `new-db`, bypassing the
  dispatch loop. Per Tool-Pair §Pair-tool writes. Renamed from
  `reset-frame-db!` (EP-0001 rf2-tfepxu, Mike ruling #10 — a db-shaped name
  never silently replaces runtime-db).

  Records a synthetic `:rf/epoch-record` so `restore-epoch!` can rewind
  the previous state; emits `:rf.epoch/db-replaced` on success. The
  runtime-db partition is preserved unchanged.

  Failure modes (each is a no-op on `app-db` and returns `false`,
  emitting a structured error trace):

    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-during-drain       — drain in flight
    :rf.epoch/replace-schema-mismatch    — new-db fails app-schema

  Dev-only — gated on `interop/debug-enabled?`. Production builds elide.

  Returns `true` on success, `false` on any failure."
  [frame-id new-db]
  (if-not interop/debug-enabled?
    false
    (let [{:keys [outcome op tags]} (tool-pair/check-replace-app-db-preconditions! frame-id new-db)]
      (case outcome
        :ok   (perform-replace-app-db! frame-id new-db)
        :fail (do (tool-pair/emit-precondition-failure! op tags)
                  false)))))

(defn reset-app-db!
  "Reset `frame-id`'s `app-db` partition to `{}`, bypassing the dispatch
  loop, while preserving live runtime-db (machines / routes / elision /
  SSR survive). The app-db-only sibling of the whole-frame `reset-frame!`
  (EP-0001 rf2-tfepxu, Mike ruling #10). Thin wrapper over `replace-app-db!`
  with the empty-map value — same synthetic-epoch recording, same gating
  and failure modes.

  Dev-only — gated on `interop/debug-enabled?`. Production builds elide.

  Returns `true` on success, `false` on any failure."
  [frame-id]
  (replace-app-db! frame-id {}))

(defn replace-runtime-db!
  "Replace `frame-id`'s `runtime-db` partition with `new-runtime-db`,
  bypassing the dispatch loop. The runtime-db sibling of `replace-app-db!`
  (Tool-Pair §Pair-tool writes). Privileged runtime / full-frame tool
  surface for injecting framework-owned subsystem state (machine
  snapshots, route slice, …); the app-db partition is preserved unchanged.

  Records a synthetic `:rf/epoch-record` so `restore-epoch!` can rewind the
  previous state; emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on `runtime-db` and returns `false`,
  emitting a structured error trace — the shared four-mutator failure
  surface per Spec 009 §Trace events):

    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-during-drain       — drain in flight
    :rf.epoch/replace-schema-mismatch    — new-runtime-db fails the
                                                   framework-owned runtime-db
                                                   validator (reg-runtime-schema)

  Dev-only — gated on `interop/debug-enabled?`. Production builds elide.

  Returns `true` on success, `false` on any failure."
  [frame-id new-runtime-db]
  (if-not interop/debug-enabled?
    false
    (let [{:keys [outcome op tags]} (tool-pair/check-replace-runtime-db-preconditions! frame-id new-runtime-db)]
      (case outcome
        :ok   (perform-replace-runtime-db! frame-id new-runtime-db)
        :fail (do (tool-pair/emit-precondition-failure! op tags)
                  false)))))

(defn replace-frame-state!
  "Replace BOTH of `frame-id`'s partitions atomically with `new-frame-state`
  (`{:rf.db/app … :rf.db/runtime …}`), bypassing the dispatch loop — the
  full-frame install for tool-driven replay / fixture install (Tool-Pair
  §Pair-tool writes). The whole-frame sibling of `replace-app-db!`; a
  db-shaped name never silently replaces runtime-db, so this is the
  explicit full-frame surface (Mike ruling #10). A missing partition key
  installs `nil` for that partition (a full-frame replace is whole-value
  by contract).

  Records a synthetic `:rf/epoch-record` so `restore-epoch!` can rewind the
  previous state; emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on the frame-state and returns `false`,
  emitting a structured error trace — the shared four-mutator failure
  surface per Spec 009 §Trace events):

    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-during-drain       — drain in flight
    :rf.epoch/replace-schema-mismatch    — the app-db partition fails
                                                   the frame's app-schema set
                                                   OR the runtime-db partition
                                                   fails the framework-owned
                                                   runtime-db validator

  Dev-only — gated on `interop/debug-enabled?`. Production builds elide.

  Returns `true` on success, `false` on any failure."
  [frame-id new-frame-state]
  (if-not interop/debug-enabled?
    false
    (let [{:keys [outcome op tags]} (tool-pair/check-replace-frame-state-preconditions! frame-id new-frame-state)]
      (case outcome
        :ok   (perform-replace-frame-state! frame-id new-frame-state)
        :fail (do (tool-pair/emit-precondition-failure! op tags)
                  false)))))

;; ---- projected egress -----------------------------------------------------
;;
;; The projection helpers live in `re-frame.epoch.tool-pair` (Phase-2
;; seam E, rf2-0wi86); the public docstrings stay here so the facade
;; remains the canonical API reference.

(defn projected-record
  "Project an `:rf/epoch-record` for off-box egress. Routes the
  app-db-rooted full-value payload slots (`:frame-state-before`,
  `:frame-state-after`, `:db-before`, `:db-after`, `:trace-events`) through
  `re-frame.elision/elide-wire-value` against the record's frame, with
  the off-box defaults `:include-sensitive? false` /
  `:include-large? false`. Sensitive paths land as `:rf/redacted`; large
  paths land as `:rf.size/large-elided` markers per the §Composition rule.

  The `:trigger-event` slot is NOT app-db-rooted (rf2-nm611o): the
  dispatched event vector's ARGS are registration-owned transient payloads
  (Spec 015 §151), the same class as the `:effects` `:args` — so it fails
  closed instead. The args are redacted while the head event-id keyword
  (the non-payload summary, == the record's `:event-id` slot) is retained,
  so `[:login \"topsecret\"]` egresses as `[:login :rf/redacted]`. The same
  event-args also ride the `:rf.event/v` / `:event` tags of every
  `:trace-events` entry; they fail closed there too. The trusted-local
  `:include-event-args? true` opt keeps the raw args.

  EP-0001 (rf2-3aizt1, decision #2 + Mike ruling #14): the CANONICAL
  `:frame-state-before` / `:frame-state-after` slots egress with their
  `:rf.db/app` partition elided (the same projection the `:db-*` app-db
  projections get) and their `:rf.db/runtime` partition DEFAULT-REDACTED
  to `:rf/redacted` off-box — machine snapshots / route slice / SSR
  metadata do not egress to AI / log channels by default.

  The structured `:sub-runs` rows are ALSO value-bearing (rf2-at60h):
  each carries the sub's computed `:prev-value` / `:value`, which respect
  the projection contract (whole-output `:large?` rows have their value
  slots substituted with the `:rf.size/large-elided` marker under the
  `:include-large? false` default). The non-value row metadata (`:sub-id`,
  `:query-v`, `:value-changed?`, `:cascade?`, `:cause-sub`,
  `:cause-event-id`) passes through unchanged.

  The structured `:effects` rows are payload-bearing too (rf2-rlt3sv):
  each carries `:args` — the RAW fx-handler argument captured verbatim from
  the `:rf.fx/args` trace tag, NOT routed through the marks-projection
  chokepoint and NOT app-db-rooted, so the schema-path walker cannot prove
  it safe. Off-box egress FAILS CLOSED: `:args` lands as `:rf/redacted` for
  every outcome row under the `:include-fx-args? false` default. The
  value-free `:fx-id` / `:outcome` / `:error-trace` row metadata and the
  whole `:renders` projection pass through unchanged. The trusted-local
  `:include-fx-args? true` opt keeps the raw `:args`. The record-level
  bookkeeping (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`,
  `:outcome`, `:halt-reason`, `:schema-digest`, `:rf.epoch/sensitive?`,
  `:rf.epoch/redacted-modified-paths-count`) also passes through
  unchanged — it carries no app-db material.

  Per Security.md §Epoch privacy posture and rf2-mrsck: this is the
  single normative projection emission site for off-box egress. Tools
  that forward epoch records across a process boundary (Xray-MCP
  `watch-epochs`, story / pair recorders, hosted post-mortem
  forwarders) MUST route through this fn at the wire boundary; the
  on-box ring buffer and `register-epoch-listener!` listener fan-out
  continue to deliver the RAW record so on-box devtools (Xray diff,
  REPL, `restore-epoch!`) can reason about exact state.

  `record` may be `nil` (e.g. a missing epoch lookup) — the projection
  returns `nil` in that case, no elision called. Production builds
  elide the entire epoch surface; consumers gate any
  `register-epoch-listener!` registration under `interop/debug-enabled?`
  per Spec 009 §User-side listener registration.

  ## Egress profile (rf2-1afn7q) + opts (rf2-5w06uu)

  The 2-arity accepts an `opts` map. The PRIMARY public selector is the
  named egress boundary `:rf.egress/profile` (the shared closed
  `re-frame.projection/profiles` enum) — it answers *\"which boundary is
  this?\"* rather than assembling boolean combinations:

    - `:rf.egress/off-box-observability` (DEFAULT) — hosted monitoring /
      log shippers / Story / pair recorders (redact sensitive, elide large,
      omit structural digests).
    - `:rf.egress/off-box-tool` — the MCP / AI / tool wire. Same
      redact/elide defaults but includes structural marker indicators
      (`:digest`) so a tool can reason about an elided large slot's shape.
      An MCP / AI epoch consumer (Xray-MCP `watch-epochs`, pair tool)
      should pass this. An unknown profile is rejected against the closed
      enum.

  The legacy unqualified `:include-*` keys are ADVANCED per-call overrides
  composed OVER the selected profile (NOT the primary boundary selector) —
  `{:include-sensitive? :include-large? :include-runtime-db?
  :include-fx-args? :include-event-args?}`, all defaulting `false`.
  `:include-sensitive?` / `:include-large?` opt the APP-DB partition's
  privacy / size posture back in across every payload slot; they do NOT
  lift the frame-state `:rf.db/runtime` partition boundary, which stays
  `:rf/redacted` unless `:include-runtime-db? true` is also passed, NOR the
  structured `:effects` `:args` (a different keyspace), which stay
  `:rf/redacted` unless `:include-fx-args? true` is passed (rf2-rlt3sv),
  NOR the `:trigger-event` / trace-event `:rf.event/v` args (another
  keyspace), which stay redacted unless `:include-event-args? true` is
  passed (rf2-nm611o). The 1-arity is the safe, fully-redacted off-box
  path."
  ([record] (tool-pair/projected-record record))
  ([record opts] (tool-pair/projected-record record opts)))

(defn projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv #(projected-record % opts) (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) can call this once
  rather than walking the raw ring and re-wrapping each record. The
  2-arity threads the trusted-local egress `opts` (rf2-5w06uu) to every
  record; the 1-arity is the safe, fully-redacted off-box path."
  ([frame-id] (tool-pair/projected-history frame-id))
  ([frame-id opts] (tool-pair/projected-history frame-id opts)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; The router calls into settle! at drain-empty; the trace surface calls
;; into capture-event! on every emit. Publishing through the late-bind
;; registry keeps router.cljc / trace.cljc free of a require on this ns.
;;
;; Per rf2-lt4e (the seventh and final per-feature split per rf2-5vjj
;; Strategy B), this namespace ships in `day8/re-frame2-epoch`; the
;; core artefact MUST NOT statically `:require` it. Core's public
;; re-exports (`rf/epoch-history`, `rf/restore-epoch!`,
;; `rf/register-epoch-listener!`, `rf/unregister-epoch-listener!`) and the
;; `(rf/configure! {:epoch-history ...})` knob look the producing fns up
;; through the hook table at call time; when this artefact is not on
;; the classpath those queries return nil / empty / false and the
;; (rf/configure! {:epoch-history ...}) call is a silent no-op — the
;; epoch surface is dev-tier so an absent artefact degrades quietly
;; rather than throwing.

;; Per rf2-rtk2e: a single map-form publication reads as 'the late-bind
;; contract for this artefact' rather than a column of identical
;; imperative side-effects. Each entry is identical to a standalone
;; `(late-bind/set-fn! key fn)` call; the drift gate
;; (`re-frame.late-bind-drift-test`) walks both call shapes.
(late-bind/set-fns!
  {;; ---- per-cascade lifecycle (router + trace capture seam) -------
   :epoch/settle!             settle!
   ;; rf2-nj6p7: per-event halt commit — the depth-exceed boundary whose
   ;; halting event never ran (so the buffer is empty and `settle!` would
   ;; skip). Synthesises the halting event's `:halted-depth` record.
   :epoch/commit-halt-record! commit-halt-record!
   :epoch/capture-event       capture/capture-event!
   ;; rf2-25zo2: in-flight cascade-cause lookup for :rf.view/rendered.
   ;; Views consume this via `:epoch/cascade-cause` at render-emit time
   ;; to stamp :cause-event-id + :cause-subs onto the per-render trace.
   :epoch/cascade-cause       capture/cascade-cause
   ;; rf2-qs6dl: post-settle render back-fill. `capture-event!` routes a
   ;; view-render op that fires with no in-flight cascade (a React-
   ;; commit-time async re-render) here so it is attributed to the
   ;; cascade that CAUSED it (the frame's most-recently-settled epoch)
   ;; rather than the next in-flight cascade. The orchestrator lives in
   ;; `re-frame.epoch.listeners` (state back-fill + listener re-notify);
   ;; publishing it through late-bind keeps `capture` free of a require
   ;; on `listeners` (which would close the assembly→capture cycle).
   :epoch/record-render!      listeners/record-render!
   ;; rf2-wi900: post-settle sub-run back-fill — the subs sibling of
   ;; `:epoch/record-render!`. Same React-deref-time async recompute
   ;; problem; same attribution fix.
   :epoch/record-sub-run!     listeners/record-sub-run!
   ;; rf2-59hx3: post-settle view-unmount back-fill — the teardown sibling
   ;; of the two above. A `:rf.view/unmounted` fires at React teardown time,
   ;; after the cascade that removed the view settled; pre-fix it was
   ;; silently dropped at the capture seam so the teardown left no signal.
   ;; Back-fills it into the causing (most-recently-settled) epoch's
   ;; `:trace-events`, where Xray's VIEWS step surfaces it.
   :epoch/record-unmount!     listeners/record-unmount!
   :epoch/on-frame-destroyed  listeners/on-frame-destroyed!

   ;; ---- introspection + Tool-Pair write surface --------------------
   :epoch/epoch-history       epoch-history
   :epoch/restore-epoch!      restore-epoch!
   :epoch/replace-app-db!     replace-app-db!
   :epoch/reset-app-db!       reset-app-db!
   :epoch/replace-runtime-db! replace-runtime-db!
   :epoch/replace-frame-state! replace-frame-state!

   ;; ---- listener + config surface ----------------------------------
   :epoch/register-epoch-listener!   register-epoch-listener!
   :epoch/unregister-epoch-listener! unregister-epoch-listener!
   :epoch/configure!                 configure!
   ;; rf2-yw1w1u: test-support config-isolation hook. `re-frame.test-
   ;; support`'s reset-hook table fires this to restore epoch config to
   ;; the shipped default between tests, so test namespaces no longer
   ;; reset the private `state/config` var directly. rf2-c0rv4v: points
   ;; straight at the `state/reset-config!` seam (no facade wrapper).
   :epoch/reset-config!              state/reset-config!
   :epoch/clear-history!             clear-history!
   :epoch/clear-epoch-listeners!     clear-epoch-listeners!

   ;; ---- off-box egress projection (rf2-mrsck) ----------------------
   ;; Per Security.md §Epoch privacy posture: off-box egress projection
   ;; helpers, parallel to elide-wire-value for direct reads. Tools that
   ;; forward records over a process boundary use these (Xray-MCP
   ;; `watch-epochs`, story / pair recorders).
   :epoch/projected-record    projected-record
   :epoch/projected-history   projected-history})
