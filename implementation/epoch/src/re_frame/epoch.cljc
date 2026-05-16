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

  Listener API (`register-epoch-cb!` / `remove-epoch-cb!`) mirrors the
  raw-trace listener API in `re-frame.trace`. Listeners receive the
  fully-assembled record after it lands in the ring buffer.

  Restore (`restore-epoch`) rewinds a frame's app-db to the named
  epoch's `:db-after`. Six documented failure modes (Tool-Pair table)
  each emit a structured trace under `:rf.epoch/*` and leave the
  frame's app-db unchanged."
  (:require [re-frame.elision :as elision]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
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

(defn- maybe-redact
  "Run the installed `:redact-fn` against `record` and return its
  result. nil `record` and nil `redact` are pass-throughs (no
  invocation, no try/catch overhead). A throwing fn emits
  `:rf.warning/epoch-redact-fn-exception` carrying `:frame` and
  `:ex-msg`, then falls back to the raw record so the drain itself
  is not broken.

  Per Tool-Pair §Time-travel §Redaction hook + Security.md §Epoch
  privacy posture (rf2-wp70d): the fn runs ONCE at build-time,
  BETWEEN `build-record` and ring-append / listener fan-out — the
  ring buffer, every `register-epoch-cb!` listener, and any off-box
  projection through `projected-record` all see the SAME redacted
  shape. The `:rf.epoch/sensitive?` rollup is computed inside
  `build-record` (which runs first) so the rollup reflects the RAW
  signal even when the fn erases the leaves it keyed on.

  Caller MUST wrap invocations in `(when interop/debug-enabled? ...)`
  — the whole epoch surface shares that gate; this helper carries no
  separate production gate.

  HOT PATH: fires once per drain-settle. Hot-path cost when no fn is
  installed is a single keyword lookup on the config map and an
  identity return."
  [record]
  (if-let [f (state/redact-fn)]
    (if (some? record)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) e
          ;; Failure isolation: emit the warning, fall back to the
          ;; raw record. The keyword literal sits inside an
          ;; `(when interop/debug-enabled? ...)` gate at the call
          ;; site (every consumer of `maybe-redact` is itself gated),
          ;; so Closure DCE elides the warning emit + literals
          ;; under :advanced + goog.DEBUG=false.
          (trace/emit! :warning :rf.warning/epoch-redact-fn-exception
                       {:frame  (:frame record)
                        :ex-msg #?(:clj (.getMessage ^Throwable e)
                                   :cljs (.-message e))})
          record))
      record)
    record))

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

(defn register-epoch-cb!
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. The id can be any comparable value; passing the
  same id twice replaces. Per Spec 009 §`register-epoch-cb!` —
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

(defn remove-epoch-cb!
  "Remove the listener registered under id."
  [id]
  (state/drop-listener! id))

(defn clear-epoch-cbs!
  []
  (state/reset-listeners!))

(defn- notify-listeners! [record]
  (let [frame-id (:frame record)]
    (doseq [[id f] (state/listeners-snapshot)]
      (state/record-observation! id frame-id)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) ex
          ;; Per Spec 009 §Listener invocation rules: listener failures
          ;; are isolated. Continue notifying. Per rf2-i5khp we ALSO
          ;; emit a structured `:rf.epoch.cb/listener-exception` trace
          ;; so devtools (re-frame-10x, Causa, pair2) can surface the
          ;; broken listener — silently swallowing the throw left tool
          ;; authors with no signal that their callback failed.
          ;;
          ;; Op-type `:rf.epoch.cb` matches the sibling
          ;; `:rf.epoch.cb/silenced-on-frame-destroy` event (per Spec
          ;; 009 §Op-type vocabulary catalogue and `epoch.cljc` row).
          ;; `:recovery :no-recovery` mirrors the `:rf.http/aborted`
          ;; trace shape — the listener's invocation is over; the next
          ;; cascade re-invokes the same fn afresh, no automatic
          ;; remediation happens between now and then.
          (trace/emit-error! :rf.epoch.cb/listener-exception
                             {:frame    frame-id
                              :cb-id    id
                              :epoch-id (:epoch-id record)
                              :message  #?(:clj  (.getMessage ^Throwable ex)
                                           :cljs (.-message ex))
                              :recovery :no-recovery}))))))

;; Forward-declare `build-record` for `on-frame-destroyed!` below;
;; the `defn-` lands in the record-assembly section. Per rf2-v0jwt
;; the destroy hook must commit a `:halted-destroy` partial record
;; before clearing the in-flight capture buffer, so it needs visibility
;; into the record builder.
(declare build-record)

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
  (when interop/debug-enabled?
    ;; Step 1: mid-drain destroy detection. The capture-buffer holds
    ;; every emit tagged with this frame, including non-cascade emits
    ;; that fire OUTSIDE a drain (e.g. `:frame/created` at reg-frame
    ;; time). Distinguish a real mid-drain destroy from
    ;; registration-time tagalongs by gating on the presence of an
    ;; `:event/run-start` emit — that is the canonical "a cascade
    ;; started inside this drain" signal. When present, commit a
    ;; partial `:halted-destroy` record so devtools (Causa,
    ;; re-frame-pair2) receive the cascade context for the destroyed-
    ;; mid-drain case. We can't read the frame's container here
    ;; (destroy-frame!'s step 6 already dissoc'd the frame record);
    ;; the partial record's `:db-before` / `:db-after` slots are nil
    ;; — the schema allows `:any`, and consumers tolerate the absent
    ;; state given `:outcome :halted-destroy` signals the destroy
    ;; context. The record is delivered to listeners only — the ring
    ;; buffer gets wiped in step 3.
    (let [buffered-events  (state/buffer-for frame-id)
          in-cascade?      (some (fn [ev]
                                   (and (= :event (:op-type ev))
                                        (= :event (:operation ev))
                                        (= :run-start (-> ev :tags :phase))))
                                 buffered-events)]
      (when in-cascade?
        ;; Per rf2-wp70d: even on the halted-destroy partial-record
        ;; commit, `maybe-redact` runs once between `build-record`
        ;; and listener fan-out so listener consumers see the SAME
        ;; redacted shape they would see for an :ok cascade record.
        (let [record (maybe-redact
                       (build-record frame-id nil nil buffered-events
                                     :halted-destroy
                                     {:operation :rf.frame/destroyed-mid-drain}))]
          (trace/emit! :rf.epoch :rf.epoch/snapshotted
                       {:frame    frame-id
                        :epoch-id (:epoch-id record)
                        :event-id (:event-id record)
                        :outcome  :halted-destroy})
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
    (state/drop-frame-buffer! frame-id)))

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

;; ---- record assembly ------------------------------------------------------
;;
;; The monotonic `:epoch-id` counter lives in `re-frame.epoch.state`
;; (Phase-2 seam A, rf2-0wi86) alongside the other shared atoms.

(defn- current-schema-digest
  "Return the live digest of the named frame's registered app-schema set,
  or nil when the schemas namespace has not registered its late-bind
  hook (e.g. an embedding host that ships no schema layer). Per Spec 010
  §Per-frame schemas the digest is frame-scoped — restore-mismatch
  reasoning runs against the frame the epoch belongs to."
  [frame-id]
  (when-let [digest (late-bind/get-fn :schemas/app-schemas-digest)]
    (try (digest frame-id)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

;; ---- sensitive rollup -----------------------------------------------------
;;
;; Per Security.md §Epoch privacy posture and rf2-mrsck: every assembled
;; record carries a top-level boolean `:rf.epoch/sensitive?` rollup so
;; listener fan-out, off-box egress, and recorder consumers can branch
;; on one slot per record (parallel to the trace-event-level
;; `:sensitive?` stamp per rf2-isdwf). Two cheap signals:
;;
;;   1. The captured trace stream already stamps `:sensitive?` per
;;      handler-meta scope — if any event in `:trace-events` carries the
;;      stamp, the record's cascade involved sensitive material.
;;   2. The frame's schema-declared `[:rf/elision :sensitive-declarations]`
;;      registry names paths that hold sensitive data; if any such path
;;      resolves to a non-nil leaf in `:db-before` or `:db-after`, the
;;      record's app-db state carries sensitive material.
;;
;; Either signal is sufficient. The check runs once at record-assembly
;; time so listeners and the projected-record helper read the rollup
;; without re-walking the record. Production builds elide the entire
;; record-assembly path — the rollup is dev-only by construction.

(defn- any-sensitive-event?
  "True when any captured trace event carries a top-level `:sensitive?
  true` stamp. Per privacy/sensitive? — the trace surface hoists the
  boolean from handler-scope-meta to the event top level (rf2-isdwf)."
  [events]
  (boolean (some privacy/sensitive? events)))

(defn- sensitive-paths-for
  "Return the schema-declared sensitive paths for `frame-id`. Empty when
  no schema layer is registered or when no slot carries
  `{:sensitive? true}`."
  [frame-id]
  (try (keys (elision/sensitive-declarations frame-id))
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- any-sensitive-leaf?
  "True when any sensitive-declared path resolves to a non-nil leaf in
  `db`. nil-leaf paths do NOT count — the path is declared sensitive
  but the slot is empty, so the record carries no actual sensitive
  material from this signal. `db` is `:db-before` or `:db-after`; both
  may be nil on halted paths (rf2-v0jwt :halted-destroy)."
  [db sensitive-paths]
  (and (some? db)
       (boolean
         (some (fn [path]
                 (some? (get-in db path)))
               sensitive-paths))))

(defn- sensitive-rollup
  "Compute the record-level `:rf.epoch/sensitive?` rollup for the
  assembled record. Returns `true` when the record's content overlaps
  a sensitive area — either via a stamped trace event or via a
  schema-declared sensitive path that holds a non-nil leaf in the
  recorded db. Returns `false` otherwise (always a strict boolean —
  consumers branch on `(true? ...)` / `(false? ...)`).

  HOT PATH: fires once per drain-settle. `sensitive-paths-for` derefs
  the elision registry once; the leaf check short-circuits at the
  first non-nil hit; the trace-event check short-circuits at the first
  stamped event. For the common case (no schema-declared sensitive
  paths, no sensitive handlers in scope) the cost is one keys-of-empty
  call plus two sequence-with-no-work walks."
  [frame-id db-before db-after events]
  (boolean
    (or (any-sensitive-event? events)
        (let [sensitive-paths (sensitive-paths-for frame-id)]
          (and (seq sensitive-paths)
               (or (any-sensitive-leaf? db-before sensitive-paths)
                   (any-sensitive-leaf? db-after  sensitive-paths)))))))

(defn- build-record
  ([frame-id db-before db-after events]
   (build-record frame-id db-before db-after events :ok nil))
  ([frame-id db-before db-after events outcome halt-reason]
   ;; Per rf2-v0jwt §Outcomes — :outcome is required and pins the
   ;; drain-boundary outcome (:ok / :halted-depth / :halted-destroy /
   ;; :halted-handler-exception); :halt-reason is a structured
   ;; descriptor populated on halt paths, absent on :ok. The schema
   ;; in Spec-Schemas §:rf/epoch-record is the canonical pin.
   ;;
   ;; Per rf2-kl5p1 (audit r3 §F1): `:event-id` and `:trigger-event`
   ;; are emitted only when `find-trigger-event` resolves them. The
   ;; schema declares `:event-id :keyword` (required, non-maybe) per
   ;; Spec-Schemas §`:rf/epoch-record` — emitting `:event-id nil` on a
   ;; halt path where no `:event/run-start` trace was buffered would
   ;; violate the schema; the open-map admits the slot's absence but
   ;; rejects a nil value. The live router halt paths already short-
   ;; circuit on an empty buffer via `(when (seq events) ...)` in
   ;; `settle!`, so the only path that can reach this branch with a
   ;; trigger-less buffer is `on-frame-destroyed!`'s `:halted-destroy`
   ;; commit; the conditional `cond->` slots make that record valid
   ;; against the schema.
   (let [{:keys [event-id event]}     (capture/find-trigger-event events)
         ;; Per rf2-ecu37: one fused walk producing all three
         ;; projections, replacing three independent transducer
         ;; passes over the same buffer. Mirrors `find-trigger-event`'s
         ;; rf2-txrq9 single-walk pattern.
         {:keys [sub-runs renders effects]} (capture/project-all events)
         ;; Per rf2-mrsck and Security.md §Epoch privacy posture:
         ;; the record-level boolean rollup mirrors the trace-event
         ;; `:sensitive?` stamp (rf2-isdwf) so listener consumers and
         ;; the projected-record helper branch on one slot per record.
         sensitive? (sensitive-rollup frame-id db-before db-after events)]
     (cond-> {:epoch-id           (state/next-epoch-id)
              :frame              frame-id
              :committed-at       (interop/now-ms)
              :db-before          db-before
              :db-after           db-after
              :outcome            outcome
              ;; Per Spec 010 §Schema digest — pinned at record time so a later
              ;; restore can compare 'recorded vs current' digests in the
              ;; :rf.epoch/restore-schema-mismatch trace tags. Optional per
              ;; Spec-Schemas §:rf/epoch-record (a host without a schema layer
              ;; produces nil; consumers tolerate the absent slot).
              :schema-digest      (current-schema-digest frame-id)
              :rf.epoch/sensitive? sensitive?
              :trace-events       events
              :sub-runs           sub-runs
              :renders            renders
              :effects            effects}
       event-id    (assoc :event-id event-id)
       event       (assoc :trigger-event event)
       halt-reason (assoc :halt-reason halt-reason)))))

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
  epoch-history vector. Listeners (`register-epoch-cb!`) receive every
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
         (let [record (maybe-redact
                        (build-record frame-id db-before db-after
                                      events outcome halt-reason))]
           (state/record! record)
           (trace/emit! :rf.epoch :rf.epoch/snapshotted
                        {:frame    frame-id
                         :epoch-id (:epoch-id record)
                         :event-id (:event-id record)
                         :outcome  outcome})
           (notify-listeners! record)))))))

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

;; ---- restore failure-mode predicates --------------------------------------

(defn- malli-validate-fn
  "Return the malli validate fn or nil.

  Per rf2-t0hq — CLJS has no runtime `resolve`, so the lookup order on
  CLJS is: late-bind hook then nil. Returning nil is treated as
  soft-pass by callers ('cannot disprove, treat as valid').

  Lookup order matches `re-frame.schemas/default-malli-validate`:
    1. Late-bind hook `:schemas/malli-validate` (published by
       `re-frame.schemas.malli` when loaded).
    2. JVM only — fall back to `(requiring-resolve 'malli.core/validate)`.
    3. Return nil (soft-pass — the schema-validate-ok? caller treats
       a nil validate fn as 'cannot disprove, treat as valid')."
  []
  (or (late-bind/get-fn :schemas/malli-validate)
      #?(:clj  (try (requiring-resolve 'malli.core/validate)
                    (catch Throwable _ nil))
         :cljs nil)))

(defn- registered-app-schemas
  "Return the {path → schema-meta} map registered against the named
  frame, or {}. Per Spec 010 §Per-frame schemas the schema set is
  frame-scoped; restore-epoch validates against the schemas registered
  against the frame the epoch belongs to, not a process-global set."
  [frame-id]
  (if-let [entries (late-bind/get-fn :schemas/frame-schema-entries)]
    (entries frame-id)
    {}))

(defn- failing-schema-paths
  "Return a vector of failing schema-paths for `db` against `frame-id`'s
  registered app-schemas. Empty vector means valid — either every
  registered schema accepted the path's value, OR no schemas are
  registered, OR no Malli validator is on the classpath. The latter
  two are soft-pass: we can't disprove validity, so we treat the db
  as valid.

  Single walk over the schema set — callers that previously chained
  `schema-validate-ok?` + `failing-paths-for` paid two walks where one
  suffices. The validity question is `(empty? (failing-schema-paths
  frame-id db))`."
  [frame-id db]
  (let [schemas  (registered-app-schemas frame-id)
        validate (malli-validate-fn)]
    (if (or (empty? schemas) (nil? validate))
      []
      (vec
        (keep (fn [[path meta]]
                (let [schema (:schema meta)
                      v      (get-in db path)]
                  (when-not (try (validate schema v)
                                 (catch #?(:clj Throwable :cljs :default) _ true))
                    path)))
              schemas)))))

(defn- machine-registration
  "Resolve a machine-id against the public machine registry. Per
  Spec 005 §Registration / §Querying machines, machines are event
  handlers whose registration metadata carries `:rf/machine? true`
  and `:rf/machine` (the spec map). Returns the registration map
  when machine-id names a registered machine, nil otherwise.

  Per rf2-ocg1: epoch restore validates against this public surface,
  not against the internal `:head` registrar kind that machines
  never used."
  [machine-id]
  (let [reg (registrar/lookup :event machine-id)]
    (when (:rf/machine? reg)
      reg)))

(defn- snapshot-version
  "Read the recorded snapshot's `:rf/snapshot-version`. Per
  Spec-Schemas §`:rf/machine-snapshot` and Spec 005 §Snapshot shape,
  the canonical slot is `[:meta :rf/snapshot-version]`."
  [snapshot]
  (get-in snapshot [:meta :rf/snapshot-version]))

(defn- machine-definition-version
  "Read the currently-registered machine definition's
  `:rf/snapshot-version`. Per Spec 005 §Snapshot shape — the
  definition's `:meta :rf/snapshot-version` is the canonical slot."
  [machine-id]
  (when-let [reg (machine-registration machine-id)]
    (let [machine (:rf/machine reg)]
      (get-in machine [:meta :rf/snapshot-version]))))

(defn- missing-references
  "Walk the recorded db for ids that are no longer present in the
  registrar. Closed v1 surface — `:rf/machines` (each machine-id
  must reference a registered machine via the public event registry,
  per Spec 005 §Registration — machines are event handlers tagged
  with `:rf/machine?`) and `:route` (`:id` must reference a registered
  :route).

  Per rf2-ocg1: machine lookup goes through the event registry, NOT
  the internal `:head` registrar kind. The latter is unrelated to
  the public machine contract.

  Returns a vector of {:kind <kind> :id <id>} entries. Empty when
  every reference resolves."
  [db]
  (let [;; Machines under :rf/machines: registered as event handlers with
        ;; :rf/machine? true (per Spec 005 §Registration).
        missing-machines
        (for [[machine-id _snapshot] (:rf/machines db)
              :when (not (machine-registration machine-id))]
          {:kind :machine :id machine-id})
        ;; Active route
        missing-route
        (when-let [route-id (get-in db [:rf/route :id])]
          (when-not (registrar/lookup :route route-id)
            [{:kind :route :id route-id}]))]
    (vec (concat missing-machines missing-route))))

(defn- machine-version-mismatch
  "Walk the recorded db's `:rf/machines` for snapshot version drift.
  The recorded snapshot may carry `:rf/snapshot-version` under
  `:meta`; the registered machine definition carries
  `:rf/snapshot-version` under its own `:meta`. When they differ,
  return the first mismatch as
  `{:machine-id <id> :recorded <int> :current <int>}`. nil when no
  mismatch is found.

  Per rf2-ocg1: both versions are read through the public Spec 005
  §Snapshot shape contract — the snapshot's `[:meta :rf/snapshot-version]`
  and the registered machine's `[:meta :rf/snapshot-version]`."
  [db]
  (some (fn [[machine-id snapshot]]
          (let [recorded (snapshot-version snapshot)]
            (when (some? recorded)
              (let [current (machine-definition-version machine-id)]
                (when (and (some? current) (not= recorded current))
                  {:machine-id machine-id
                   :recorded   recorded
                   :current    current})))))
        (:rf/machines db)))

;; ---- restore --------------------------------------------------------------

(defn- find-epoch-in
  "Search a resolved history vector for the record matching `epoch-id`.
  Caller has already paid the `@histories` deref — `check-restore-
  preconditions!` reads history once at the top and reuses the vector
  for both the lookup and the `:history-size` count on the
  unknown-epoch failure path (rf2-3g7x3 — was two derefs)."
  [history epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
        history))

(defn- emit-precondition-failure!
  [operation tags]
  (trace/emit-error! operation
                     (assoc tags :recovery :no-recovery)))

(defn- drain-in-flight?
  "True when `frame-record`'s router is mid-drain (sync or async).
  Shared by every precondition path that must refuse to write to
  `app-db` while a cascade is being processed."
  [frame-record]
  (let [router (:router frame-record)
        r      (when router @router)]
    (boolean (and r (or (:in-drain? r) (:in-sync-drain? r))))))

(defn- frame-exists-or-fail
  "Resolve `frame-id` to its `frame-record` or yield the canonical
  no-such-handler precondition-failure result. Returns
  `{:outcome :ok :frame-record <record>}` or
  `{:outcome :fail :op :rf.error/no-such-handler
    :tags {:kind :frame :frame frame-id}}`. Shared by every Tool-Pair /
  time-travel write surface so the no-such-handler tag shape stays
  canonical."
  [frame-id]
  (if-let [frame-record (frame/frame frame-id)]
    {:outcome :ok :frame-record frame-record}
    {:outcome :fail
     :op      :rf.error/no-such-handler
     :tags    {:kind  :frame
               :frame frame-id}}))

(defn- check-restore-preconditions!
  "Validate the seven documented preconditions for restoring `frame-id`
  to `epoch-id`. Returns a result map:

    {:outcome :ok :epoch <epoch>}
                 — all checks passed; `:epoch` is the resolved history
                   record whose `:db-after` is the restore target.
    {:outcome :fail :op <kw> :tags <map>}
                 — first failing check; `:op` is the trace operation
                   the caller must emit, `:tags` are its tags. No
                   trace events are emitted from inside this helper —
                   emission is the caller's job so the
                   precondition test stays a pure data check.

  Failure modes preserve the exact operation keywords and tag shapes
  the public surface has always emitted (see `restore-epoch`'s
  docstring for the catalogue)."
  [frame-id epoch-id]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (:frame-record frame-result))
      {:outcome :fail
       :op      :rf.epoch/restore-during-drain
       :tags    {:frame    frame-id
                 :epoch-id epoch-id}}

      :else
      (let [history (epoch-history frame-id)
            epoch   (find-epoch-in history epoch-id)]
        (cond
          ;; (3) Epoch present in current history?
          (nil? epoch)
          {:outcome :fail
           :op      :rf.epoch/restore-unknown-epoch
           :tags    {:frame        frame-id
                     :epoch-id     epoch-id
                     :history-size (count history)}}

          ;; (3a) Halted-cascade target? Per rf2-v0jwt: an epoch whose
          ;; :outcome is not :ok records partial state the cascade
          ;; never settled to, so it is not a valid restore target.
          ;; Refuse before the schema / handler / version checks so
          ;; the failure surfaces with the actual halt context, not
          ;; a downstream consequence of the partial db.
          (not= :ok (get epoch :outcome :ok))
          {:outcome :fail
           :op      :rf.epoch/restore-non-ok-record
           :tags    {:frame       frame-id
                     :epoch-id    epoch-id
                     :outcome     (:outcome epoch)
                     :halt-reason (:halt-reason epoch)}}

          :else
          (let [db-target (:db-after epoch)]
            ;; Each helper is called once and its result bound, so the
            ;; failure path walks the recorded db / schema set / machine
            ;; map exactly once per check (rf2-081zk).
            (if-let [failing-paths (seq (failing-schema-paths frame-id db-target))]
              ;; (4) Schema mismatch?
              ;; Per Spec 010 §Schema digest + Tool-Pair §Time-travel:
              ;; the trace carries both the digest pinned on the
              ;; epoch record (recorded) and the current frame's
              ;; live digest, so pair tools can pinpoint *what
              ;; changed* about the schema set, not merely *that*
              ;; it changed.
              {:outcome :fail
               :op      :rf.epoch/restore-schema-mismatch
               :tags    {:frame                  frame-id
                         :epoch-id               epoch-id
                         :schema-digest-recorded (:schema-digest epoch)
                         :schema-digest-current  (current-schema-digest frame-id)
                         :failing-paths          (vec failing-paths)}}

              (if-let [missing (seq (missing-references db-target))]
                ;; (5) Missing handler referenced from db?
                {:outcome :fail
                 :op      :rf.epoch/restore-missing-handler
                 :tags    {:frame    frame-id
                           :epoch-id epoch-id
                           :missing  (vec missing)}}

                (if-let [{:keys [machine-id recorded current]} (machine-version-mismatch db-target)]
                  ;; (6) Machine snapshot version drift?
                  {:outcome :fail
                   :op      :rf.epoch/restore-version-mismatch
                   :tags    {:frame            frame-id
                             :epoch-id         epoch-id
                             :machine-id       machine-id
                             :version-recorded recorded
                             :version-current  current}}

                  {:outcome :ok :epoch epoch})))))))))

(defn- perform-restore!
  "Carry out the actual `app-db` rewind once preconditions have passed.
  Replaces the frame's container with `epoch`'s `:db-after` and emits
  `:rf.epoch/restored`. Returns `true`."
  [frame-id epoch]
  (let [container (frame/get-frame-db frame-id)
        db-target (:db-after epoch)]
    (adapter/replace-container! container db-target)
    (trace/emit! :rf.epoch :rf.epoch/restored
                 {:frame    frame-id
                  :epoch-id (:epoch-id epoch)})
    true))

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
    (let [{:keys [outcome epoch op tags]} (check-restore-preconditions! frame-id epoch-id)]
      (case outcome
        :ok   (perform-restore! frame-id epoch)
        :fail (do (emit-precondition-failure! op tags)
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
;; gate as `restore-epoch` / `register-epoch-cb!` / the rest of the
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

(defn- check-reset-frame-db-preconditions!
  "Validate the three documented preconditions for `reset-frame-db!`.
  Returns `{:outcome :ok}` when all checks pass, otherwise
  `{:outcome :fail :op <kw> :tags <map>}` matching the precondition-
  failure shape of `check-restore-preconditions!`. Pure data — no
  trace events emitted from here; emission is the caller's job."
  [frame-id new-db]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (:frame-record frame-result))
      {:outcome :fail
       :op      :rf.epoch/reset-frame-db-during-drain
       :tags    {:frame frame-id}}

      :else
      ;; (3) Schema mismatch? Single walk — `failing-schema-paths`
      ;; returns the failing paths (or [] for the valid / soft-pass
      ;; cases), folding what was previously a two-helper / two-walk
      ;; chain into one.
      (let [failing (failing-schema-paths frame-id new-db)]
        (if (seq failing)
          {:outcome :fail
           :op      :rf.epoch/reset-frame-db-schema-mismatch
           :tags    {:frame         frame-id
                     :failing-paths failing}}
          {:outcome :ok})))))

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
    (let [record (maybe-redact
                   (assoc (build-record frame-id db-before new-db [])
                          :event-id      :rf.epoch/db-replaced
                          :trigger-event [:rf.epoch/db-replaced]))]
      (state/record! record)
      (trace/emit! :rf.epoch :rf.epoch/db-replaced
                   {:frame    frame-id
                    :epoch-id (:epoch-id record)})
      (notify-listeners! record))
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
    (let [{:keys [outcome op tags]} (check-reset-frame-db-preconditions! frame-id new-db)]
      (case outcome
        :ok   (perform-reset-frame-db! frame-id new-db)
        :fail (do (emit-precondition-failure! op tags)
                  false)))))

;; ---- projected egress -----------------------------------------------------
;;
;; Per Security.md §Epoch privacy posture and rf2-mrsck: the framework's
;; single normative projection emission site for off-box epoch egress.
;; The in-process ring buffer (`epoch-history`) and `register-epoch-cb!`
;; listener fan-out deliver RAW records — restore-epoch and on-box
;; devtools (Causa diff, REPL inspection) need them. Tools that
;; egress an epoch record across a process boundary (Causa-MCP
;; `watch-epochs`, story / pair recorders, hosted forwarders) MUST
;; route through `projected-record` first, parallel to how direct-read
;; tools route through `elide-wire-value` (per rf2-czv3p).
;;
;; The projection wraps `re-frame.elision/elide-wire-value` against the
;; record's frame-id and the four payload-bearing slots that may carry
;; sensitive or large material: `:db-before`, `:db-after`,
;; `:trigger-event`, and `:trace-events`. The cheap structured slots
;; (`:sub-runs`, `:renders`, `:effects`) carry no app-db material —
;; they project sub-ids, render-keys, fx-ids, and outcome tags — so
;; the projection leaves them as-is. The record-level bookkeeping
;; (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:outcome`,
;; `:halt-reason`, `:schema-digest`, `:rf.epoch/sensitive?`) is
;; structurally non-sensitive and passes through.
;;
;; Per-tool reimplementation of the projection is prohibited (the
;; same posture as the wire-elision walker). New egress tools call
;; `projected-record` and trust the contract.

(defn- elide-payload-slot
  "Walk one payload slot through `elide-wire-value` rooted at the named
  frame, with off-box defaults (`:include-sensitive? false`,
  `:include-large? false`). Returns the elided value; `nil` slots are
  preserved as nil (a halted-destroy record's `:db-before` /
  `:db-after` are nil per rf2-v0jwt; the schema admits the absent /
  nil slot — the projection MUST NOT fabricate a value)."
  [v frame-id]
  (when (some? v)
    (elision/elide-wire-value v {:frame frame-id})))

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
  on-box ring buffer and `register-epoch-cb!` listener fan-out
  continue to deliver the RAW record so on-box devtools (Causa diff,
  REPL, `restore-epoch`) can reason about exact state.

  `record` may be `nil` (e.g. a missing epoch lookup) — the projection
  returns `nil` in that case, no elision called. Production builds
  elide the entire epoch surface; consumers gate any
  `register-epoch-cb!` registration under `interop/debug-enabled?`
  per Spec 009 §User-side listener registration."
  [record]
  (when (map? record)
    (let [frame-id (:frame record)]
      (cond-> record
        (contains? record :db-before)
        (update :db-before     elide-payload-slot frame-id)

        (contains? record :db-after)
        (update :db-after      elide-payload-slot frame-id)

        (contains? record :trigger-event)
        (update :trigger-event elide-payload-slot frame-id)

        (contains? record :trace-events)
        (update :trace-events  elide-payload-slot frame-id)))))

(defn projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv projected-record (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) can call this once
  rather than walking the raw ring and re-wrapping each record."
  [frame-id]
  (mapv projected-record (epoch-history frame-id)))

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
;; `rf/register-epoch-cb!`, `rf/remove-epoch-cb!`) and the
;; `(rf/configure :epoch-history ...)` knob look the producing fns up
;; through the hook table at call time; when this artefact is not on
;; the classpath those queries return nil / empty / false and the
;; (rf/configure :epoch-history ...) call is a silent no-op — the
;; epoch surface is dev-tier so an absent artefact degrades quietly
;; rather than throwing.

(late-bind/set-fn! :epoch/settle!             settle!)
(late-bind/set-fn! :epoch/discard-buffer!     discard-buffer!)
(late-bind/set-fn! :epoch/capture-event       capture/capture-event!)
(late-bind/set-fn! :epoch/epoch-history       epoch-history)
(late-bind/set-fn! :epoch/restore-epoch       restore-epoch)
(late-bind/set-fn! :epoch/reset-frame-db!     reset-frame-db!)
(late-bind/set-fn! :epoch/register-epoch-cb!  register-epoch-cb!)
(late-bind/set-fn! :epoch/remove-epoch-cb!    remove-epoch-cb!)
(late-bind/set-fn! :epoch/configure!          configure!)
(late-bind/set-fn! :epoch/clear-history!      clear-history!)
(late-bind/set-fn! :epoch/clear-epoch-cbs!    clear-epoch-cbs!)
(late-bind/set-fn! :epoch/on-frame-destroyed  on-frame-destroyed!)
;; Per rf2-mrsck and Security.md §Epoch privacy posture: off-box
;; egress projection helpers, parallel to elide-wire-value for direct
;; reads. Tools that forward records over a process boundary use
;; these (Causa-MCP `watch-epochs`, story / pair recorders).
(late-bind/set-fn! :epoch/projected-record    projected-record)
(late-bind/set-fn! :epoch/projected-history   projected-history)
