(ns re-frame.epoch
  "Per-frame epoch history. Per Tool-Pair §Time-travel and Spec-Schemas
  §`:rf/epoch-record`.

  Every event-cascade settle (drain reaching empty queue) marks an epoch
  boundary. The runtime records, per frame, an `:rf/epoch-record` with:

    :epoch-id       opaque, unique within a frame's history
    :frame          frame keyword
    :committed-at   timestamp
    :event-id       the event keyword that triggered the cascade
    :trigger-event  the full event vector
    :db-before      app-db snapshot before the cascade
    :db-after       app-db snapshot after the drain settled
    :trace-events   the raw trace stream that produced this epoch
    :sub-runs       structured projection of subscription activity
    :renders        structured projection of render activity
    :effects        structured projection of fx-walk activity

  Records are kept in a per-frame ring buffer (default depth 50,
  configurable via `(rf/configure :epoch-history {:depth N})`). Older
  records are evicted when the buffer is full.

  The entire epoch-history machinery is gated on `interop/debug-enabled?`,
  the same compile-time goog-define as the trace surface. Production
  builds elide; no allocation, no storage, no overhead.

  Listener API (`register-epoch-listener!` / `unregister-epoch-listener!`) mirrors the
  raw-trace listener API in `re-frame.trace`. Listeners receive the
  fully-assembled record after it lands in the ring buffer.

  Restore (`restore-epoch`) rewinds a frame's app-db to the named
  epoch's `:db-after`. Six documented failure modes (Tool-Pair table)
  each emit a structured trace under `:rf.epoch/*` and leave the
  frame's app-db unchanged."
  (:require [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.listeners :as listeners]
            [re-frame.epoch.state :as state]
            [re-frame.epoch.write :as write]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- configuration --------------------------------------------------------
;;
;; Atoms, defaults, and config-merge validation live in `re-frame.epoch.state`
;; (Phase-2 seam A, rf2-0wi86). The facade keeps the public docstrings and
;; the late-bind hook publication.

(defn configure!
  "Update the epoch-history configuration. Supported keys:

    :depth              N — non-negative integer; ring-buffer depth
                        per frame. 0 disables recording (assembled
                        records can still fire on listeners but
                        nothing lands in the ring buffer).
    :trace-events-keep  N — non-negative integer; cap how many of
                        the MOST-RECENT records per frame retain
                        their raw `:trace-events` vector. Older
                        records keep the cheap structured
                        projections (`:sub-runs` / `:renders` /
                        `:effects`) but drop `:trace-events` to
                        bound memory. Per Spec-Schemas
                        §`:rf/epoch-record` line 2224
                        (`:trace-events` is optional —
                        'implementations may choose to drop traces
                        from older epochs') and refactor-audit r2
                        (rf2-lwn4t) §F3.1.
    :redact-fn          fn? or nil; per Tool-Pair §Time-travel
                        §Redaction hook and Security.md §Epoch
                        privacy posture (rf2-wp70d). When non-nil
                        the runtime invokes the fn once per
                        assembled record between `build-record` and
                        ring-append / listener fan-out, so the ring
                        and every listener see the SAME redacted
                        shape. The `:rf.epoch/sensitive?` rollup is
                        computed BEFORE the fn runs — the rollup
                        remains accurate even when the fn erases
                        the leaves it keyed on. A throwing fn emits
                        `:rf.warning/epoch-redact-fn-exception` and
                        falls back to the raw record for that drain
                        only (the drain itself is not broken).
                        Passing `nil` clears any previously-installed
                        fn.

  Per rf2-mrsck and Security.md §Epoch privacy posture: the default
  `:trace-events-keep` is a FINITE value (5) — the most-recent five
  records per frame retain raw `:trace-events`; older records keep
  only the cheap `:sub-runs` / `:renders` / `:effects` projections.
  Apps that need the whole ring's raw streams pass an explicit
  larger value (or a value >= the depth cap). Passing `nil`
  explicitly via `(rf/configure :epoch-history {:trace-events-keep
  nil})` is a no-op against the explicit-value validation below;
  use a numeric value or do not pass the slot at all.

  Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: keys are validated at
  the boundary. A `:depth` or `:trace-events-keep` that isn't a
  non-negative integer is silently dropped from `opts` rather than
  stored — a `nil` or non-numeric value would otherwise survive
  configuration and explode at the next `record!` call when `pos?` /
  `nat-int?` runs on the stored value. `:redact-fn` accepts `fn?`
  or `nil` (explicit clear); other shapes are silently dropped.
  Validation mirrors the pattern `re-frame.trace/configure-trace-
  buffer!` applies at its own config boundary."
  [opts]
  (state/merge-config! opts))

(defn current-config
  "Return the current epoch-history configuration map. Public for tests
  and tools that want to display the current depth."
  []
  (state/current-config))

;; The record-assembly helpers — `maybe-redact`, `current-schema-digest`,
;; the sensitive-rollup family, and `build-record` itself — live in
;; `re-frame.epoch.assembly` (Phase-2 seam D, rf2-0wi86).

;; ---- the per-frame ring buffer --------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history": last N epochs per frame.
;; Stored as a map of frame-id → vector (oldest-first). New records append
;; to the back; the front evicts when the buffer exceeds the configured
;; depth. The atom + ring-buffer mutators live in
;; `re-frame.epoch.state` (Phase-2 seam A, rf2-0wi86).

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

(defn- clear-frame-history!
  "Drop every recorded epoch for the named frame. Per rf2-sh5g6: no
  late-bind hook is published for this; the fn is invoked only from
  the in-artefact test pin (via the `#'epoch/clear-frame-history!`
  var). The test fixture uses the unscoped `clear-history!`
  hook so scoped clearing is not on the integration critical path —
  marking `defn-` keeps the surface area of the epoch public API
  tight without losing the pinned-seam test."
  [frame-id]
  (state/drop-frame-history! frame-id))

;; ---- listener registry ----------------------------------------------------
;;
;; The listener / observed-frames atoms and their low-level CRUD live in
;; `re-frame.epoch.state` (Phase-2 seam A, rf2-0wi86); the facade keeps
;; the public docstrings and the fan-out / failure-isolation policy.

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
;; `re-frame.epoch.listeners` (Phase-2 seam C, rf2-0wi86).

(defn on-frame-destroyed!
  "Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656)
  and rf2-v0jwt §Outcomes (`:halted-destroy`):

    1. Mid-drain destroy detection — if `capture-buffers[frame-id]`
       holds buffered events at destroy time, this is a mid-drain
       destroy (the handler that called `destroy-frame!` was running
       inside the drain; its trace events were captured into the
       in-flight buffer). Notify epoch listeners with a partial
       `:halted-destroy` record carrying the cascade's traces. The
       record is NOT appended to the ring buffer — step 3 wipes the
       ring buffer for the destroyed frame regardless. Devtools that
       care about destroyed cascades receive the record via the
       listener fan-out before the ring buffer is dropped.
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

  Called from `re-frame.frame/destroy-frame!` via the
  `:epoch/on-frame-destroyed` late-bind hook. Idempotent across
  repeated destroys of the same frame — once a cb's entry no longer
  contains the frame-id, no further trace fires for that pair, and
  the (already-cleared) ring-buffer / capture-buffer entries stay
  absent."
  [frame-id]
  (listeners/on-frame-destroyed! frame-id))

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

;; ---- drain-settle hook ----------------------------------------------------

(defn settle!
  "Hook called by the router on every drain boundary — clean settle AND
  halts. Per Tool-Pair §Time-travel and rf2-v0jwt §Outcomes.

  Arities:
    (settle! frame-id db-before db-after)
      Clean drain-settle. `:outcome` is `:ok`. Equivalent to passing
      `:ok` as `outcome` explicitly. Skips recording when the captured
      buffer is empty (a truly empty cascade — likely a rejected
      dispatch — is degenerate and would emit a misleading record).
    (settle! frame-id db-before db-after outcome halt-reason)
      Drain-boundary commit with explicit outcome. `outcome` is one of
      `:ok` / `:halted-depth` / `:halted-destroy` /
      `:halted-handler-exception`; `halt-reason` is a structured
      descriptor populated on halt paths (nil on `:ok`). On a buffer
      with no recoverable trigger (no `:event/run-start` and no
      `:event-id` tag — e.g. a destroy that races a registration-time
      emit) `build-record` omits `:event-id` / `:trigger-event`
      entirely; the schema admits absent slots, rejects nil values
      (per rf2-kl5p1 / audit r3 §F1).

  `db-before` is the app-db value snapshotted before the cascade began;
  `db-after` is the value the runtime settled to — equal to `db-before`
  for atomic-rollback halts (`:halted-depth`), the live container value
  for the destroy path (`:halted-destroy`), the post-drain value for
  `:ok`. The captured trace buffer is harvested here and projected into
  the record.

  Emits `:rf.epoch/snapshotted` with a `:outcome` tag so trace listeners
  can discriminate clean from halted boundaries without inspecting the
  epoch-history vector. Listeners (`register-epoch-listener!`) receive every
  record regardless of outcome."
  ([frame-id db-before db-after]
   (settle! frame-id db-before db-after :ok nil))
  ([frame-id db-before db-after outcome halt-reason]
   (when interop/debug-enabled?
     (let [events (state/harvest-buffer! frame-id)]
       ;; Empty-buffer policy (consistent across outcomes): an empty
       ;; capture buffer means no cascade context was recorded for
       ;; this frame — skip emission rather than commit a record with
       ;; no :event-id / :trigger-event. For halt outcomes, the
       ;; cooperating seam (e.g. on-frame-destroyed harvesting events
       ;; in the mid-drain-destroy path) emits the partial record;
       ;; this seam fires when a router-only halt path (e.g. depth-
       ;; exceeded with an in-flight cascade) holds the events.
       (when (seq events)
         ;; Per rf2-wp70d / Tool-Pair §Time-travel §Redaction hook:
         ;; `maybe-redact` runs ONCE per record between
         ;; `build-record` and ring-append / listener fan-out so the
         ;; ring and listeners see the SAME redacted shape. The
         ;; `:rf.epoch/sensitive?` rollup inside `build-record` runs
         ;; FIRST — the rollup reflects raw signals even when the
         ;; redact-fn erases the leaves it keyed on.
         (let [record (assembly/maybe-redact
                        (assembly/build-record frame-id db-before db-after
                                               events outcome halt-reason))]
           (state/record! record)
           (trace/emit! :rf.epoch :rf.epoch/snapshotted
                        {:frame    frame-id
                         :epoch-id (:epoch-id record)
                         :event-id (:event-id record)
                         :outcome  outcome})
           (listeners/notify-listeners! record)))))))

(defn- discard-buffer!
  "Drop the in-flight capture buffer for frame-id WITHOUT committing a
  record. Used by routes that intentionally suppress the cascade
  surface (e.g. the rf2-zzper destroy hook's belt-and-braces buffer
  clear, where on-frame-destroyed runs before the drain loop's halt
  path observes the destroy).

  Per rf2-hul9q: the only consumer is the late-bind seam, surfaced
  through `:epoch/discard-buffer!`. The fn itself takes no direct
  callers, so the visibility stays `defn-` to keep the late-bind
  seam the sole public access path.

  Per rf2-v0jwt: the router's halt paths no longer route through this
  hook — they call `settle!` with a halt outcome so a `:halted-*`
  epoch record is committed. `discard-buffer!` remains for the
  destroy-hook belt-and-braces path."
  [frame-id]
  (when interop/debug-enabled?
    (state/harvest-buffer! frame-id))
  nil)

;; ---- restore --------------------------------------------------------------
;;
;; Precondition validators, schema/handler/version probes, and
;; `perform-restore!` live in `re-frame.epoch.write` (Phase-2 seam E,
;; rf2-0wi86). The orchestrator below stays in the facade — it wires
;; the precondition check + the trace emission + the perform step into
;; a four-line case-match.

(defn restore-epoch
  "Rewind the frame's `app-db` to the named epoch's `:db-after`. Emits
  `:rf.epoch/restored` on success.

  Failure modes (each is a no-op on `app-db` and emits a structured
  error trace):

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
    (let [{:keys [outcome epoch op tags]} (write/check-restore-preconditions! frame-id epoch-id)]
      (case outcome
        :ok   (write/perform-restore! frame-id epoch)
        :fail (do (write/emit-precondition-failure! op tags)
                  false)))))

;; ---- reset-frame-db! (Tool-Pair §Pair-tool writes, rf2-zq55) -------------
;;
;; Per Tool-Pair §Pair-tool writes: a public Tool-Pair write surface that
;; replaces a frame's `app-db` with an arbitrary new value, bypassing the
;; dispatch loop. Used by pair-shaped tools for state injection (evolved-
;; state-shape probes after a handler hot-swap), story tools, conformance
;; harnesses, and time-travel from JSON-loaded bug repros.
;;
;; The surface is dev-only — gated on `interop/debug-enabled?`, the same
;; gate as `restore-epoch` / `register-epoch-listener!` / the rest of the
;; epoch-history machinery. Production builds (`:advanced` +
;; goog.DEBUG=false) elide the body via Closure DCE; the surface is not
;; available in shipped binaries.
;;
;; Failure modes (each is a no-op on `app-db` and returns `false`):
;;   :rf.error/no-such-handler            (kind :frame) — frame not registered
;;   :rf.epoch/reset-frame-db-during-drain — called while drain is in flight
;;   :rf.epoch/reset-frame-db-schema-mismatch — `new-db` fails the frame's
;;                                              registered app-schema set
;;
;; On success: records a synthetic `:rf/epoch-record` (so undo via
;; `restore-epoch` works against the previous state), emits
;; `:rf.epoch/db-replaced`, replaces the container, and fires registered
;; epoch listeners with the assembled record.

(defn- perform-reset-frame-db!
  "Carry out the `app-db` replacement once preconditions have passed.
  Records a synthetic `:rf/epoch-record` (so `restore-epoch` can rewind
  the prior state), emits `:rf.epoch/db-replaced`, replaces the
  container, and fans the record out to registered listeners. Returns
  `true`."
  [frame-id new-db]
  (let [container (frame/get-frame-db frame-id)
        db-before (when container (adapter/read-container container))]
    (adapter/replace-container! container new-db)
    ;; Record a synthetic epoch so `restore-epoch` can rewind the
    ;; previous state. The record's :trigger-event is the
    ;; pair-tool injection sentinel (no application event ran).
    ;; Per rf2-wp70d: `maybe-redact` runs once between assembly and
    ;; the record!/notify-listeners! split so ring + listeners see
    ;; the SAME redacted shape on this synthetic record too.
    (let [record (assembly/maybe-redact
                   (assoc (assembly/build-record frame-id db-before new-db [])
                          :event-id      :rf.epoch/db-replaced
                          :trigger-event [:rf.epoch/db-replaced]))]
      (state/record! record)
      (trace/emit! :rf.epoch :rf.epoch/db-replaced
                   {:frame    frame-id
                    :epoch-id (:epoch-id record)})
      (listeners/notify-listeners! record))
    true))

(defn reset-frame-db!
  "Replace `frame-id`'s `app-db` with `new-db`, bypassing the dispatch
  loop. Per Tool-Pair §Pair-tool writes (rf2-zq55).

  Records a synthetic `:rf/epoch-record` so `restore-epoch` can rewind
  the previous state; emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on `app-db` and returns `false`,
  emitting a structured error trace):

    :rf.error/no-such-handler                 — frame not registered
    :rf.epoch/reset-frame-db-during-drain     — drain in flight
    :rf.epoch/reset-frame-db-schema-mismatch  — new-db fails app-schema

  Dev-only — gated on `interop/debug-enabled?`. Production builds elide.

  Returns `true` on success, `false` on any failure."
  [frame-id new-db]
  (if-not interop/debug-enabled?
    false
    (let [{:keys [outcome op tags]} (write/check-reset-frame-db-preconditions! frame-id new-db)]
      (case outcome
        :ok   (perform-reset-frame-db! frame-id new-db)
        :fail (do (write/emit-precondition-failure! op tags)
                  false)))))

;; ---- projected egress -----------------------------------------------------
;;
;; The projection helpers live in `re-frame.epoch.write` (Phase-2
;; seam E, rf2-0wi86); the public docstrings stay here so the facade
;; remains the canonical API reference.

(defn projected-record
  "Project an `:rf/epoch-record` for off-box egress. Routes the four
  payload-bearing slots (`:db-before`, `:db-after`, `:trigger-event`,
  `:trace-events`) through `re-frame.elision/elide-wire-value` against
  the record's frame, with the off-box defaults
  `:include-sensitive? false` / `:include-large? false`. Sensitive
  paths land as `:rf/redacted`; large paths land as
  `:rf.size/large-elided` markers per the §Composition rule. The
  record-level bookkeeping (`:epoch-id`, `:frame`, `:committed-at`,
  `:event-id`, `:outcome`, `:halt-reason`, `:schema-digest`,
  `:rf.epoch/sensitive?`) and the cheap structured projections
  (`:sub-runs`, `:renders`, `:effects`) pass through unchanged —
  they carry no app-db material.

  Per Security.md §Epoch privacy posture and rf2-mrsck: this is the
  single normative projection emission site for off-box egress. Tools
  that forward epoch records across a process boundary (Causa-MCP
  `watch-epochs`, story / pair recorders, hosted post-mortem
  forwarders) MUST route through this fn at the wire boundary; the
  on-box ring buffer and `register-epoch-listener!` listener fan-out
  continue to deliver the RAW record so on-box devtools (Causa diff,
  REPL, `restore-epoch`) can reason about exact state.

  `record` may be `nil` (e.g. a missing epoch lookup) — the projection
  returns `nil` in that case, no elision called. Production builds
  elide the entire epoch surface; consumers gate any
  `register-epoch-listener!` registration under `interop/debug-enabled?`
  per Spec 009 §User-side listener registration."
  [record]
  (write/projected-record record))

(defn projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv projected-record (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) can call this once
  rather than walking the raw ring and re-wrapping each record."
  [frame-id]
  (write/projected-history frame-id))

;; ---- late-bind hook registration ------------------------------------------
;;
;; The router calls into settle! at drain-empty; the trace surface calls
;; into capture-event! on every emit. Publishing through the late-bind
;; registry keeps router.cljc / trace.cljc free of a require on this ns.
;;
;; Per rf2-lt4e (the seventh and final per-feature split per rf2-5vjj
;; Strategy B), this namespace ships in `day8/re-frame2-epoch`; the
;; core artefact MUST NOT statically `:require` it. Core's public
;; re-exports (`rf/epoch-history`, `rf/restore-epoch`,
;; `rf/register-epoch-listener!`, `rf/unregister-epoch-listener!`) and the
;; `(rf/configure :epoch-history ...)` knob look the producing fns up
;; through the hook table at call time; when this artefact is not on
;; the classpath those queries return nil / empty / false and the
;; (rf/configure :epoch-history ...) call is a silent no-op — the
;; epoch surface is dev-tier so an absent artefact degrades quietly
;; rather than throwing.

(late-bind/set-fn! :epoch/settle!             settle!)
(late-bind/set-fn! :epoch/discard-buffer!     discard-buffer!)
(late-bind/set-fn! :epoch/capture-event       capture/capture-event!)
;; rf2-25zo2: in-flight cascade-cause lookup for :rf.view/rendered. Views
;; consume this via `:epoch/cascade-cause` at render-emit time to stamp
;; :cause-event-id + :cause-subs onto the per-render trace.
(late-bind/set-fn! :epoch/cascade-cause       capture/cascade-cause)
(late-bind/set-fn! :epoch/epoch-history       epoch-history)
(late-bind/set-fn! :epoch/restore-epoch       restore-epoch)
(late-bind/set-fn! :epoch/reset-frame-db!     reset-frame-db!)
(late-bind/set-fn! :epoch/register-epoch-listener!  register-epoch-listener!)
(late-bind/set-fn! :epoch/unregister-epoch-listener!    unregister-epoch-listener!)
(late-bind/set-fn! :epoch/configure!          configure!)
(late-bind/set-fn! :epoch/clear-history!      clear-history!)
(late-bind/set-fn! :epoch/clear-epoch-listeners!    clear-epoch-listeners!)
(late-bind/set-fn! :epoch/on-frame-destroyed  listeners/on-frame-destroyed!)
;; Per rf2-mrsck and Security.md §Epoch privacy posture: off-box
;; egress projection helpers, parallel to elide-wire-value for direct
;; reads. Tools that forward records over a process boundary use
;; these (Causa-MCP `watch-epochs`, story / pair recorders).
(late-bind/set-fn! :epoch/projected-record    projected-record)
(late-bind/set-fn! :epoch/projected-history   projected-history)
