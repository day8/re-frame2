(ns re-frame.epoch.assembly
  "Pure assembly of raw `:rf/epoch-record` values.

  Two responsibilities live here:

    1. `build-record`   — produces one record per dequeued event and delegates
                          capture-buffer walks to
                          `re-frame.epoch.capture`.
    2. `sensitive-rollup` — computes `:rf.epoch/sensitive?` from its two
                          raw signals, each public on its own:
                          `sensitive-trace?` (trace-event stamps) and
                          `sensitive-db?` (frame-declared sensitive paths).

  The ring and listeners retain raw replay material; redaction happens at
  off-box egress inside `re-frame.epoch.tool-pair/project-record`.

  `current-schema-digest` pins the schema identity later compared by restore
  preconditions."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.epoch.capture :as rf.epoch.capture]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.trace :as rf.trace]))

;; ---- consumer-facing outcome enum ----------------------------------------
;;
;; The record outcome carries the detailed close cause
;; of an epoch's close — `:ok` / `:halted-depth` / `:halted-destroy` /
;; `:halted-handler-exception`. Trace consumers also receive a coarse tier:
;;
;;     :ok                       → :ok       (the cascade settled cleanly)
;;     :halted-depth             → :blocked  (the drain hit the depth limit;
;;                                            the halting event never ran)
;;     :halted-destroy           → :blocked  (a destroy requested during the
;;                                            drain dropped queued work at the
;;                                            event boundary — a deliberate
;;                                            lifecycle stop, not an error)
;;     :halted-handler-exception → :error    (schema-reserved cause; the
;;                                            reference runtime
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

(defn emit-snapshotted!
  "Emit the one cascade-trailer trace for a committed epoch,
  `:rf.epoch/snapshotted`. It carries the detailed `:outcome` cause and the
  consumer-facing `:summary` tier (via `outcome->consumer-facing`), so a
  consumer reads either off the same trace. Spec 009 §Op-type vocabulary
  holds the tag contract and the cause→summary table.

  Shared by the per-event clean/halt settle (`re-frame.epoch/commit-record!`)
  and the frame-destroy commit (`re-frame.epoch.listeners/on-frame-destroyed!`)
  so the trailer shape lives in one place — a future trailer tag lands on
  both surfaces at once. The op is catalogued in
  `re-frame.epoch.capture/skip-ops` (it fires after the buffer is
  harvested, outside any cascade)."
  [frame-id epoch-id event-id outcome]
  (rf.trace/emit! :rf.epoch :rf.epoch/snapshotted
                  {:frame             frame-id
                   :rf.epoch/id       epoch-id
                   :rf.trace/event-id event-id
                   :outcome           outcome
                   :summary           (outcome->consumer-facing outcome)}))

;; ---- schema-digest --------------------------------------------------------

(defn current-schema-digest
  "Return the live digest of the app-schema set the named frame resolves,
  or nil when the schemas namespace has not registered its late-bind
  hook (e.g. an embedding host that ships no schema layer). The digest
  follows the frame's own resolution (Spec 010 §Schemas resolve through the
  frame's images), so restore-mismatch reasoning runs against the frame the
  epoch belongs to."
  ([frame-id]
   (current-schema-digest frame-id (constantly true)))
  ([frame-id continue?]
   (when (continue?)
     (when-let [schema-digest-fn (rf.late-bind/get-fn :schemas/app-schemas-digest)]
       (try
         (schema-digest-fn {:frame frame-id})
         (catch #?(:clj Throwable :cljs :default) _
           ;; Optional diagnostic enrichment degrades to nil on failure. The
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
;;   2. The classification policy the record keeps — the one its projection
;;      redacts under — names app-db paths that hold sensitive data; if any
;;      such path resolves to a non-nil leaf in `:db-before` or `:db-after`,
;;      the record's app-db state carries sensitive material.
;;
;; Either signal is sufficient. The check runs once at record-assembly
;; time so listeners and the projection helper read the rollup
;; without re-walking the record. Production builds elide the entire
;; record-assembly path — the rollup is dev-only by construction.
;; Security.md §Epoch privacy posture: one reader, one declaration source.
;;
;; Each signal is also public on its own, because the two answer different
;; questions: the trace-event signal says the CASCADE handled sensitive
;; material, the app-db signal only that app-db HOLDS some. A consumer that
;; gates on the cascade (Xray's epoch ingest, tools/xray/spec/013 §One policy,
;; three ingress paths) reads `sensitive-trace?` and leaves held values to its
;; per-path redaction.

(defn sensitive-trace?
  "The trace-event signal: true when any of `events` carries a top-level
  `:sensitive? true` stamp, which the trace surface hoists from handler scope.
  `events` is a record's `:trace-events`."
  [events]
  (boolean (some rf.privacy/sensitive? events)))

(defn- sensitive-paths-for
  "Return the classified sensitive paths for `frame-id`. Empty when
  the frame classifies no `:sensitive` app-db path (its `reg-classification`
  and flow-output declarations, never schema props)."
  [frame-id]
  (try (keys (rf.elision/sensitive-declarations frame-id))
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- policy-sensitive-paths
  "The app-db paths classification `policy` claims `:sensitive`."
  [policy]
  (keys (get-in policy [:app-db :sensitive])))

(defn- sensitive-leaves
  "The non-nil values each sensitive declaration reaches in `db`, as
  `{decl-path {concrete-path value}}`. Read through the egress walker's own
  collection matching (`re-frame.elision/declared-values`), so an index-free
  `[:items :token]` reaches every element's `:token` and `[:accounts :token]`
  reaches `[:accounts \"a\" :token]` — the same nodes the projection redacts. A
  nil value does not count: the slot is declared sensitive but holds nothing.
  A nil `db` holds no leaf."
  [db sensitive-paths]
  (into {}
        (keep (fn [[decl-path by-path]]
                (let [non-nil (into {} (remove (comp nil? val)) by-path)]
                  (when (seq non-nil) [decl-path non-nil]))))
        (rf.elision/declared-values db sensitive-paths)))

(defn- any-sensitive-leaf?
  "True when any sensitive declaration reaches a non-nil leaf in `db`
  (`sensitive-leaves`). `db` is `:db-before` or `:db-after`."
  [db sensitive-paths]
  (boolean (seq (sensitive-leaves db sensitive-paths))))

(defn- holds-sensitive-leaf?
  "True when one of `sensitive-paths` reaches a non-nil leaf in `db-before` or
  `db-after`."
  [sensitive-paths db-before db-after]
  (boolean
    (and (seq sensitive-paths)
         (or (any-sensitive-leaf? db-before sensitive-paths)
             (any-sensitive-leaf? db-after  sensitive-paths)))))

(defn sensitive-db?
  "The app-db signal under frame `frame-id`'s current classification: true
  when a path it classifies `:sensitive` holds a non-nil leaf in `db-before`
  or `db-after`. A record's own rollup reads the policy the record keeps
  instead (`sensitive-rollup`). The leaf check walks only the subtrees a
  declaration can reach."
  [frame-id db-before db-after]
  (holds-sensitive-leaf? (sensitive-paths-for frame-id) db-before db-after))

(defn sensitive-rollup
  "Compute the record-level `:rf.epoch/sensitive?` rollup for the
  assembled record: `sensitive-trace?` OR the app-db signal under `policy`,
  the classification policy the record keeps. Returns `true`
  when the record's content overlaps a sensitive area — either via a stamped
  trace event or via a path `policy` declares sensitive that holds a non-nil
  leaf in the recorded db. Returns `false` otherwise (always a strict
  boolean — consumers branch on `(true? ...)` / `(false? ...)`).

  Runs once per dequeued event. The trace-event check runs first and
  short-circuits at the first stamped event. For the common case (no
  declared sensitive paths, no sensitive handlers in scope) the cost is
  one keys-of-empty call plus one sequence-with-no-work walk."
  [policy db-before db-after events]
  (or (sensitive-trace? events)
      (holds-sensitive-leaf? (policy-sensitive-paths policy) db-before db-after)))

;; ---- redacted-modified-paths-count ----------------------------------------
;;
;; A post-projection diff cannot reveal a sensitive change when both sides are
;; the same redaction sentinel. The raw record therefore carries the number of
;; classified sensitive app-db paths whose values differ between before and
;; after. It uses value equality and counts nil/non-nil transitions. Paths the
;; projection redacts without a sensitive declaration cannot be inferred here
;; and are deliberately outside this count.

(defn redacted-modified-paths-count
  "Compute the record-level `:rf.epoch/redacted-modified-paths-count`
  rollup for the assembled record. Returns the integer count of the
  sensitive paths `policy` — the classification policy the record keeps —
  declares whose value differs between
  `db-before` and `db-after` — for a collection declaration, the whole set
  of leaves it reaches (`sensitive-leaves`).

  Runs once per dequeued event. Each side is walked only along
  the subtrees a declaration can reach. For the common case (no
  declared sensitive paths) the cost is one keys-of-empty call.

  Returns `0` when:
    - `policy` classifies no `:sensitive` app-db path, OR
    - No classified-sensitive path's value differs across the cascade.

  Halted records: `db-before` and/or `db-after` may be `nil` on the
  `:halted-destroy` path. A nil db reaches no leaf, so a path holding a
  value on the other side counts as a change."
  [policy db-before db-after]
  (let [sensitive-paths (policy-sensitive-paths policy)]
    (if (empty? sensitive-paths)
      0
      (let [before (sensitive-leaves db-before sensitive-paths)
            after  (sensitive-leaves db-after sensitive-paths)]
        (count (remove #(= (get before %) (get after %)) sensitive-paths))))))

;; ---- record assembly ------------------------------------------------------

(defn- policy-for-record
  "The classification policy a record of frame `frame-id` keeps: the frame's
  current policy, with the one its before snapshot `frame-state-before` was
  taken under (`re-frame.elision/record-policy-for`). A `:halted-destroy`
  record is assembled once its frame no longer resolves, over the frame-state
  the last settled record committed, so it keeps that record's policy. Spec 015
  §Retained values keep their recorded classification."
  [frame-id frame-state-before outcome]
  (or (when (= :halted-destroy outcome)
        (some-> (peek (rf.epoch.state/history-for frame-id)) rf.elision/recorded-policy))
      (rf.elision/record-policy-for frame-id frame-state-before)))

(defn- with-flow-definitions
  "`record` carrying, in metadata, the flow definitions frame `frame-id`'s flow
  memo held for each of its two snapshots (`:flows/memo-definitions`), so a
  restore re-seeds the memo the restored frame-state was computed under. Like
  the recorded policy, it rides in metadata and never egresses. Without the
  flows artefact the record carries none."
  [record frame-id frame-state-before]
  (if-let [memo-definitions (rf.late-bind/get-fn :flows/memo-definitions)]
    (vary-meta record assoc ::flow-definitions
               (memo-definitions frame-id frame-state-before))
    record))

(defn ^:no-doc flow-definitions
  "The `{flow-id definition}` the flow memo held for `record`'s snapshot in
  direction `to` (`:before` / `:after`), or nil when it carries none."
  [record to]
  (get (::flow-definitions (meta record)) to))

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
   ;; `:db-before` / `:db-after` slots are the OPTIONAL app-db
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
   ;; rejects a nil value. A depth halt and a `replace-frame-state!`
   ;; injection both assemble with no run-start buffered: each caller pins
   ;; its own trigger afterwards, and the remaining trigger slots stay
   ;; absent.
   (let [;; App-db projection of the canonical frame-state — the `:db-before`
         ;; / `:db-after` slots + the sensitive-rollup signal. `frame-state`
         ;; may be nil on a halted-destroy path whose pre-cascade snapshot is
         ;; absent; `(:rf.db/app nil)` is nil, which consumers already tolerate.
         db-before (get frame-state-before rf.frame/app-partition-key)
         db-after  (get frame-state-after  rf.frame/app-partition-key)
         {:keys [event-id event dispatch-id fx-overrides interceptor-overrides]
          trigger-cofx :rf.cofx}
         (rf.epoch.capture/find-trigger-event events)
         ;; One fused walk produces all structured projections.
         {:keys [sub-runs renders effects]} (rf.epoch.capture/project-all events)
         ;; The record's classification: the projection redacts under it and
         ;; both app-db signals below read it.
         policy (policy-for-record frame-id frame-state-before outcome)
         ;; Listener and egress consumers can branch on one record-level slot.
         ;; Sensitive declarations target app-db paths, so it reasons over
         ;; the app-db projection (`db-before` / `db-after`).
         sensitive? (sensitive-rollup policy db-before db-after events)
         ;; Count declared sensitive
         ;; paths whose value differs between :db-before / :db-after.
         ;; Surfaces the suppressed signal directly on the record, so Xray
         ;; can show that something changed when both sides redact to an
         ;; empty diff. Computed from the RAW values, before the off-box
         ;; projection substitutes the sentinel (parallel to the
         ;; :rf.epoch/sensitive? rollup above).
         redacted-modified-path-count (redacted-modified-paths-count
                                        policy db-before db-after)]
     (cond-> {;; The record's own DISCRIMINATOR. `rf/project-egress`
              ;; dispatches RECORD KINDS on this slot, so the epoch arm resolves
              ;; its per-kind projector through the late-bound
              ;; `:epoch/project-record` hook rather than the door bare-walking
              ;; the record as a kindless tree from `:path []` (which would ship
              ;; `:db-*` slots RAW — a frame's `[:auth :token]` declaration never
              ;; matches `[:db-after :auth :token]`). Fixed value, pinned by
              ;; Spec-Schemas §`:rf/epoch-record`. It is a STAMP, not storage:
              ;; it adds nothing to the raw ring beyond this one slot.
              :kind               :rf/epoch-record
              :epoch-id           (rf.epoch.state/next-epoch-id)
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
       interceptor-overrides (assoc :interceptor-overrides interceptor-overrides)
       ;; The record's classification policy, kept by reference in metadata:
       ;; the record's app-db slots egress under the classification they were
       ;; recorded with after a `reg-classification` is replaced, or a flow
       ;; output the event itself vacated leaves the live policy (Spec 015
       ;; §Retained values keep their recorded classification).
       :always (rf.elision/record-policy policy)
       ;; The flow definitions each snapshot was computed under, for a
       ;; restore to re-seed (Spec 013 §Re-registration).
       :always (with-flow-definitions frame-id frame-state-before)))))
