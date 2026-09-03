(ns re-frame.epoch.assembly
  "Pure assembly of raw `:rf/epoch-record` values.

  Three responsibilities live here:

    1. `build-record`   — produces one record per dequeued event and delegates
                          capture-buffer walks to
                          `re-frame.epoch.capture`.
    2. `sensitive-rollup` — computes `:rf.epoch/sensitive?` from raw
                          signals (trace-event stamps + frame-declared
                          sensitive paths).
    3. `apply-redact-fn` — runs the installed `:redact-fn` advanced
                           override at off-box egress only. The ring and
                           listeners retain raw replay material; redaction
                           happens inside
                          `re-frame.epoch.tool-pair/projected-record`.

  `current-schema-digest` pins the schema identity later compared by restore
  preconditions."
  (:require [re-frame.elision :as elision]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.state :as state]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.trace :as trace]))

;; ---- consumer-facing outcome enum ----------------------------------------
;;
;; The record outcome carries the detailed close cause
;; of an epoch's close — `:ok` / `:halted-depth` / `:halted-destroy` /
;; `:halted-handler-exception`. Trace consumers also receive a coarse tier:
;;
;;     :ok                       → :ok       (the cascade settled cleanly)
;;     :halted-depth             → :blocked  (the drain hit the depth limit;
;;                                            the halting event never ran)
;;     :halted-destroy           → :blocked  (the frame was destroyed mid-
;;                                            drain — a deliberate lifecycle
;;                                            stop, not an error)
;;     :halted-handler-exception → :error    (schema-reserved cause; the
;;                                            reference runtime currently
;;                                            does NOT emit this — the
;;                                            interceptor error-capture seam
;;                                            settles such cascades :ok with
;;                                            the error trace under
;;                                            :trace-events. The mapping is
;;                                            pinned for a future runtime
;;                                            that aborts the drain on a
;;                                            handler throw.)
(defn outcome->consumer-facing
  "Project the detailed record outcome onto `:ok`, `:blocked`, or `:error`."
  [outcome]
  (case outcome
    :ok                       :ok
    :halted-depth             :blocked
    :halted-destroy           :blocked
    :halted-handler-exception :error))

(defn emit-snapshotted+outcome!
  "Emit the paired cascade-trailer trace ops for a committed epoch:
  `:rf.epoch/snapshotted` carrying the detailed `:outcome` CAUSE, then
  `:rf.epoch/outcome` carrying the consumer-facing `{:ok :blocked :error}`
  tier (via `outcome->consumer-facing`). Both ops share the same
  `:frame` / `:rf.epoch/id` / `:rf.trace/event-id` so consumers correlate
  the detailed cause with the coarse summary.

  Shared by the per-event clean/halt settle (`re-frame.epoch/commit-record!`)
  and the mid-drain destroy commit (`re-frame.epoch.listeners/on-frame-
  destroyed!`) so the two-op trailer shape lives in one place — a future
  trailer tag lands on both surfaces at once. Both ops are catalogued in
  `re-frame.epoch.capture/skip-ops` (they fire after the buffer is
  harvested, outside any cascade)."
  ([frame-id epoch-id event-id outcome]
   (emit-snapshotted+outcome! frame-id epoch-id event-id outcome
                              (constantly true)))
  ([frame-id epoch-id event-id outcome continue?]
   (when (continue?)
     (trace/emit! :rf.epoch :rf.epoch/snapshotted
                  {:frame             frame-id
                   :rf.epoch/id       epoch-id
                   :rf.trace/event-id event-id
                   :outcome           outcome}))
   ;; Trace listeners are synchronous. A snapshotted listener may destroy the
   ;; exact frame owner, in which case the paired outcome fact is suppressed.
   (when (continue?)
     (trace/emit! :rf.epoch :rf.epoch/outcome
                  {:frame             frame-id
                   :rf.epoch/id       epoch-id
                   :rf.trace/event-id event-id
                   :outcome           (outcome->consumer-facing outcome)}))))

;; ---- projection-side redaction override ----------------------------------

(defn apply-redact-fn
  "Apply the configured advanced override to an already-projected record.

  Nil record or nil override is an identity operation. A throwing override
  emits `:rf.warning/epoch-redact-fn-exception`, including frame and qualified
  epoch identity, then returns the built-in projected record. This runs only at
  egress; raw replay storage and listener delivery are never changed. Callers
  own the shared debug gate."
  [record]
  (if-let [redact-fn (state/redact-fn)]
    (if (some? record)
      (try
        (redact-fn record)
        (catch #?(:clj Throwable :cljs :default) redaction-error
          ;; Failure isolation: emit the warning, fall back to the
          ;; projected record. The keyword literal sits inside an
          ;; `(when interop/debug-enabled? ...)` gate at the call site
          ;; (the projection helper is itself gated), so Closure DCE
          ;; elides the warning emit + literals under :advanced +
          ;; goog.DEBUG=false.
          ;; Trace identity is qualified; the record field read below is bare.
          (trace/emit! :warning :rf.warning/epoch-redact-fn-exception
                       {:frame       (:frame record)
                        :rf.epoch/id (:epoch-id record)
                        :ex-msg      #?(:clj (.getMessage ^Throwable redaction-error)
                                        :cljs (.-message redaction-error))})
          record))
      record)
    record))

;; ---- schema-digest --------------------------------------------------------

(defn current-schema-digest
  "Return the live digest of the named frame's registered app-schema set,
  or nil when the schemas namespace has not registered its late-bind
  hook (e.g. an embedding host that ships no schema layer). Per Spec 010
  §Per-frame schemas the digest is frame-scoped — restore-mismatch
  reasoning runs against the frame the epoch belongs to."
  ([frame-id]
   (current-schema-digest frame-id (constantly true)))
  ([frame-id continue?]
   (when (continue?)
     (when-let [schema-digest-fn (late-bind/get-fn :schemas/app-schemas-digest)]
       (try
         (schema-digest-fn frame-id)
         (catch #?(:clj Throwable :cljs :default) _
           ;; Optional diagnostic enrichment retains nil-on-failure. The
           ;; caller's post-callback exact check decides whether even that nil
           ;; result remains usable.
           nil))))))

;; ---- sensitive rollup -----------------------------------------------------
;;
;; Every assembled record carries a top-level boolean
;; `:rf.epoch/sensitive?` rollup so
;; listener fan-out, off-box egress, and recorder consumers can branch
;; on one slot per record (parallel to the trace-event-level
;; `:sensitive?` stamp). Two signals contribute:
;;
;;   1. The captured trace stream already stamps `:sensitive?` per
;;      handler-meta scope — if any event in `:trace-events` carries the
;;      stamp, the record's cascade involved sensitive material.
;;   2. The frame's frame-declared `[:rf.runtime/elision :sensitive-declarations]`
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
  true` stamp. The trace surface hoists this boolean from handler scope."
  [events]
  (boolean (some privacy/sensitive? events)))

(defn- sensitive-paths-for
  "Return the classified sensitive paths for `frame-id`. Empty when
  the frame classifies no `:sensitive` app-db path (EP-0025 — the
  `[:rf.runtime/elision :sensitive-declarations]` registry is written by the
  commit-plane classification effects, not schema-populated)."
  [frame-id]
  (try (keys (elision/sensitive-declarations frame-id))
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- any-sensitive-leaf?
  "True when any sensitive-declared path resolves to a non-nil leaf in
  `db`. nil-leaf paths do NOT count — the path is declared sensitive
  but the slot is empty, so the record carries no actual sensitive
  material from this signal. `db` is `:db-before` or `:db-after`; both
  may be nil on halted paths."
  [db sensitive-paths]
  (and (some? db)
       (boolean
         (some (fn [path]
                 (some? (get-in db path)))
               sensitive-paths))))

(defn sensitive-rollup
  "Compute the record-level `:rf.epoch/sensitive?` rollup for the
  assembled record. Returns `true` when the record's content overlaps
  a sensitive area — either via a stamped trace event or via a
  frame-declared sensitive path that holds a non-nil leaf in the
  recorded db. Returns `false` otherwise (always a strict boolean —
  consumers branch on `(true? ...)` / `(false? ...)`).

  Runs once per dequeued event. `sensitive-paths-for` derefs
  the elision registry once; the leaf check short-circuits at the
  first non-nil hit; the trace-event check short-circuits at the first
  stamped event. For the common case (no frame-declared sensitive
  paths, no sensitive handlers in scope) the cost is one keys-of-empty
  call plus two sequence-with-no-work walks."
  [frame-id db-before db-after events]
  (boolean
    (or (any-sensitive-event? events)
        (let [sensitive-paths (sensitive-paths-for frame-id)]
          (and (seq sensitive-paths)
               (or (any-sensitive-leaf? db-before sensitive-paths)
                   (any-sensitive-leaf? db-after  sensitive-paths)))))))

;; ---- redacted-modified-paths-count ----------------------------------------
;;
;; A post-projection diff cannot reveal a sensitive change when both sides are
;; the same redaction sentinel. The raw record therefore carries the number of
;; classified sensitive app-db paths whose values differ between before and
;; after. It uses value equality and counts nil/non-nil transitions. An opaque
;; `:redact-fn` may redact additional undeclared paths; those cannot be inferred
;; here and are deliberately outside this count.

(defn redacted-modified-paths-count
  "Compute the record-level `:rf.epoch/redacted-modified-paths-count`
  rollup for the assembled record. Returns the integer count of
  frame-declared sensitive paths whose value differs between
  `db-before` and `db-after`.

  Runs once per dequeued event. `sensitive-paths-for` derefs
  the elision registry once and the walk is O(P) where P is the
  declared-sensitive-path count for the frame — typically a small
  constant (apps declare a handful of `[:auth :password]`-shaped
  paths). For the common case (no frame-declared sensitive paths)
  the cost is one keys-of-empty call and an empty-reduce.

  Returns `0` when:
    - No paths are classified sensitive (the frame classifies no
      `:sensitive` app-db path), OR
    - No classified-sensitive path's value differs across the cascade.

  Halted records: `db-before` and/or `db-after` may be `nil` on the
  `:halted-destroy` path. `(get-in nil P)` is `nil`;
  the predicate `(not= a b)` handles the nil/non-nil edge correctly
  (counts as a change when one side is nil and the other isn't)."
  [frame-id db-before db-after]
  (let [sensitive-paths (sensitive-paths-for frame-id)]
    (if (empty? sensitive-paths)
      0
      (reduce
        (fn [modified-path-count path]
          (if (not= (get-in db-before path)
                    (get-in db-after  path))
            (inc modified-path-count)
            modified-path-count))
        0
        sensitive-paths))))

;; ---- record assembly ------------------------------------------------------

(defn build-record
  "Assemble a `:rf/epoch-record`. `committed-at` is the record's durable
  causal time: the committing token's `:rf/time-ms`, threaded from the
  router rather than read from the host during assembly. This makes it
  replayable: replaying the
  same event log with the same supplied `:rf/time-ms` values produces records
  with equal `:committed-at`, and a restored frame-state's recorded
  timestamps are not silently reinterpreted against a new ambient clock.

  `build-record` is a pure data builder — it performs NO clock read of its
  own. The two-arg-fewer arity defaults `outcome` to `:ok` and `halt-reason`
  to nil; both arities require `committed-at` (callers without a token — the
  pair-tool synthetic db-replace injections, which run with no application
  event in flight — pass `(interop/epoch-now-ms)` explicitly at the call
  site, where the ambient wall-clock read describes the tool action. It is
  the wall-clock surface, NOT `interop/now-ms` — `:committed-at` is a
  durable field, kept wall-clock-class for cross-epoch comparison)."
  ([frame-id frame-state-before frame-state-after events committed-at]
   (build-record frame-id frame-state-before frame-state-after events committed-at :ok nil))
  ([frame-id frame-state-before frame-state-after events committed-at outcome halt-reason]
   (build-record frame-id frame-state-before frame-state-after events committed-at
                 outcome halt-reason (current-schema-digest frame-id)))
  ([frame-id frame-state-before frame-state-after events committed-at outcome halt-reason
    schema-digest]
   ;; The canonical snapshot is the whole frame state: both partitions.
   ;; `restore-epoch!` rewinds to `:frame-state-after`, reviving machines /
   ;; routes / elision / SSR runtime-db state, not just app-db. The
   ;; `:db-before` / `:db-after` slots are kept as the OPTIONAL app-db
   ;; PROJECTION (`(:rf.db/app frame-state-…)`) so pair tools can render
   ;; app-db diffs cheaply without re-projecting (Spec-Schemas
   ;; §`:rf/epoch-record`). The two `db-*` projections are also what the
   ;; sensitive-path rollup + redacted-modified count read — frame-declared
   ;; sensitive declarations target app-db paths, so the rollup
   ;; reasons over the app-db projection, not the whole frame-state.
   ;;
   ;; `:outcome` is required and pins the event-boundary result. The runtime
   ;; commits one of three:
   ;; :ok / :halted-depth / :halted-destroy. (:halted-handler-exception
   ;; is a schema-reserved value the runtime never emits — handler
   ;; exceptions ride the interceptor error-capture seam and the drain
   ;; settles :ok with the error trace under :trace-events; see
   ;; Spec-Schemas §:rf/epoch-record §Outcomes.) :halt-reason is a
   ;; structured descriptor populated on halt paths, absent on :ok. The
   ;; schema in Spec-Schemas §:rf/epoch-record is the canonical pin.
   ;;
   ;; Emit trigger slots only when `find-trigger-event` resolves them. The
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
   (let [;; App-db projection of the canonical frame-state — the `:db-before`
         ;; / `:db-after` slots + the sensitive-rollup signal. `frame-state`
         ;; may be nil on a halted-destroy path whose pre-cascade snapshot is
         ;; absent; `(:rf.db/app nil)` is nil, which consumers already tolerate.
         db-before (get frame-state-before frame/app-partition-key)
         db-after  (get frame-state-after  frame/app-partition-key)
         {:keys [event-id event dispatch-id fx-overrides interceptor-overrides]
          trigger-cofx :rf.cofx}
         (capture/find-trigger-event events)
         ;; One fused walk produces all structured projections.
         {:keys [sub-runs renders effects]} (capture/project-all events)
         ;; Listener and egress consumers can branch on one record-level slot.
         ;; Sensitive declarations target app-db paths, so it reasons over
         ;; the app-db projection (`db-before` / `db-after`).
         sensitive? (sensitive-rollup frame-id db-before db-after events)
         ;; Count frame-declared sensitive
         ;; paths whose value differs between :db-before / :db-after.
         ;; Closes Xray's "redact-fn ⇒ empty diff but something changed"
         ;; gap by surfacing the suppressed signal directly on the
         ;; record. Computed BEFORE :redact-fn runs (parallel to the
         ;; :rf.epoch/sensitive? rollup above).
         redacted-modified-path-count (redacted-modified-paths-count
                                        frame-id db-before db-after)]
     (cond-> {:epoch-id           (state/next-epoch-id)
              :frame              frame-id
              ;; Durable causal time is supplied from envelope construction;
              ;; assembly never re-reads the host clock.
              :committed-at       committed-at
              ;; Whole frame state before and after; restore uses the latter.
              :frame-state-before frame-state-before
              :frame-state-after  frame-state-after
              ;; OPTIONAL app-db projection — cheap tool diffs.
              :db-before          db-before
              :db-after           db-after
              :outcome            outcome
              ;; Pin schema identity so restore can compare recorded vs current.
              ;; Optional per
              ;; Spec-Schemas §:rf/epoch-record (a host without a schema layer
              ;; produces nil; consumers tolerate the absent slot).
              :schema-digest      schema-digest
              :rf.epoch/sensitive? sensitive?
              :rf.epoch/redacted-modified-paths-count redacted-modified-path-count
              :trace-events       events
              :sub-runs           sub-runs
              :renders            renders
              :effects            effects}
       event-id    (assoc :event-id event-id)
       event       (assoc :trigger-event event)
       ;; Pin the post-generation flat `:rf.cofx` replay
       ;; token (the causal cofx as it was after the router's declared-only
       ;; delivery ran: every generator-backed recordable fact minted at
       ;; processing-start written back into the in-flight `:rf.cofx`, plus
       ;; the framework `:rf/time-ms`). It is the slot a Tool-Pair replay
       ;; supplies alongside `:rf.cofx/mint-policy :strict` to re-present the
       ;; exact facts the original run consumed. Spelled bare per record-layer
       ;; vocabulary (Spec-Schemas §`:rf/epoch-record`). A halt path with no
       ;; `:event/run-start` buffered,
       ;; or a production build whose dev-only run-start cofx tag is elided,
       ;; omits the slot — which the open-map schema admits. The marks
       ;; chokepoint redacts declared-sensitive recordable facts at the emit
       ;; site, so the value pinned here is already projection-safe.
       trigger-cofx (assoc :rf.cofx trigger-cofx)
       ;; Pin the settling event's `:dispatch-id` as a
       ;; first-class record slot. It is the stable cross-counter-space
       ;; link between the epoch ring (epoch-id space) and the raw trace
       ;; stream's cascade list (dispatch-id space) that Xray's
       ;; `:rf.xray/focus` correlation pivots on. Pinned here — not
       ;; re-derived from `:trace-events` at read time — so the link
       ;; survives `:trace-events-keep` elision on older records and the
       ;; post-settle reactive back-fill (which pads `:trace-events` with
       ;; nil-`:dispatch-id` sub-run / render events). Optional per
       ;; Spec-Schemas §`:rf/epoch-record`: a cascade whose trace carried
       ;; no `:dispatch-id` (a rejected dispatch, a halt before run-start)
       ;; omits the slot, matching `:event-id` / `:trigger-event`.
       dispatch-id (assoc :dispatch-id dispatch-id)
       halt-reason (assoc :halt-reason halt-reason)
       ;; Pin the envelope's serializable per-call and lexical
       ;; `:fx-overrides` (fn-valued entries already marker-ized to
       ;; `:rf/fn-override` at the router's emission site) and per-call
       ;; `:interceptor-overrides` (EDN by construction — EP-0022) as
       ;; first-class record slots, spelled BARE to match the dispatch-opts
       ;; key names a Tool-Pair strict replay splats straight back in
       ;; alongside `:rf.cofx` (Tool-Pair §Replay). Omitted (not the shared
       ;; empty sentinel) on the override-free hot path and on a halt path
       ;; with no `:event/run-start` buffered — the open-map schema admits
       ;; the slot's absence, matching `:event-id` / `:trigger-event` /
       ;; `:rf.cofx` above.
       fx-overrides (assoc :fx-overrides fx-overrides)
       interceptor-overrides (assoc :interceptor-overrides interceptor-overrides)))))
