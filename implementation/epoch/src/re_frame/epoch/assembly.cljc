(ns re-frame.epoch.assembly
  "Record assembly — the pure-data builder that takes a frame-id, the
  before/after app-db snapshots, and the harvested cascade trace
  events, and yields a fully-formed `:rf/epoch-record` map.

  Three responsibilities live here:

    1. `build-record`   — produces the record (one allocation per
                          DEQUEUED EVENT — per rf2-nj6p7 the epoch
                          boundary is the event, not the drain).
                          Delegates the cascade-buffer walks to
                          `re-frame.epoch.capture`.
    2. `sensitive-rollup` — computes `:rf.epoch/sensitive?` from raw
                          signals (trace-event stamps + frame-declared
                          sensitive paths).
    3. `apply-redact-fn` — runs the installed `:redact-fn` advanced
                          override at the OFF-BOX EGRESS boundary only
                          (EP-0015 §15 + open-issue 6, RULED). The ring
                          buffer + listener fan-out deliver the RAW
                          record (causal replay material); redaction
                          happens projection-side, inside
                          `re-frame.epoch.tool-pair/projected-record`.

  Plus the `current-schema-digest` accessor that both `build-record`
  (digest pinned on the record) and the restore-preconditions seam
  (`re-frame.epoch.tool-pair`) consume.

  Per rf2-0wi86 Phase-2 seam D: this namespace is dependency-free
  beyond state (atoms + counter), capture (cascade walks), and the
  shared `re-frame.elision` / `re-frame.late-bind` / `re-frame.privacy`
  / `re-frame.trace` libs. No upstream seam consumes it; downstream
  seams (write, listeners, facade orchestrators) call into it."
  (:require [re-frame.elision :as elision]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.state :as state]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.trace :as trace]))

;; ---- consumer-facing outcome enum (rf2-18g1w) -----------------------------
;;
;; The runtime's `:rf/epoch-record :outcome` enum carries the detailed CAUSE
;; of an epoch's close — `:ok` / `:halted-depth` / `:halted-destroy` /
;; `:halted-handler-exception` (per Spec-Schemas §`:rf/epoch-record`
;; §Outcomes, rf2-v0jwt). Consumers (Xray's Trace panel close-row, Story
;; outcome chips) usually want a coarser tier: "did this epoch land
;; cleanly, was it stopped, or did it error?". Per rf2-jppad / rf2-18g1w
;; the runtime emits the consumer-facing tier directly as the separate
;; trace op `:rf.epoch/outcome` (Spec 009) so the consuming spec
;; (`tools/xray/spec/023-Trace-Panel.md` §13) doesn't have to re-derive
;; the projection. The cause-enum on the epoch record stays; the new op
;; is additive.
;;
;; Mapping (pinned in `epoch-test/outcome-enum-projection-pins-mapping`;
;; load-bearing — devtools and trace-stream consumers depend on it):
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
  "Project the detailed `:outcome` cause from the `:rf/epoch-record` enum
  (`:ok` / `:halted-depth` / `:halted-destroy` / `:halted-handler-exception`,
  per Spec-Schemas §`:rf/epoch-record` §Outcomes, rf2-v0jwt) onto the
  three-tier consumer-facing summary (`:ok` / `:blocked` / `:error`, per
  `tools/xray/spec/023-Trace-Panel.md` §13). Pure; total over the four
  schema-pinned cause values. Per rf2-jppad / rf2-18g1w."
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
  the detailed cause with the coarse summary (rf2-18g1w / rf2-jppad).

  Shared by the per-event clean/halt settle (`re-frame.epoch/commit-record!`)
  and the mid-drain destroy commit (`re-frame.epoch.listeners/on-frame-
  destroyed!`) so the two-op trailer shape lives in one place — a future
  trailer tag lands on both surfaces at once. Both ops are catalogued in
  `re-frame.epoch.capture/skip-ops` (they fire after the buffer is
  harvested, outside any cascade)."
  [frame-id epoch-id event-id outcome]
  (trace/emit! :rf.epoch :rf.epoch/snapshotted
               {:frame             frame-id
                :rf.epoch/id       epoch-id
                :rf.trace/event-id event-id
                :outcome           outcome})
  (trace/emit! :rf.epoch :rf.epoch/outcome
               {:frame             frame-id
                :rf.epoch/id       epoch-id
                :rf.trace/event-id event-id
                :outcome           (outcome->consumer-facing outcome)}))

;; ---- projection-side redaction override (EP-0015 §15 / open-issue 6) ------

(defn apply-redact-fn
  "Apply the installed `:redact-fn` advanced override against an
  already-projected `record` and return its result. nil `record` and
  nil `redact` are pass-throughs (no invocation, no try/catch
  overhead). A throwing fn emits `:rf.warning/epoch-redact-fn-exception`
  carrying `:frame`, `:epoch-id`, and `:ex-msg`, then falls back to the
  projected record so the egress itself is not broken. The warning's
  epoch-identity tag is the canonical qualified `:rf.epoch/id` (rf2-ifdsar);
  `:frame` is the bare-carve-out routing tag.

  Per EP-0015 §15 (Epoch Redaction) + open-issue 6 disposition (RULED,
  hardened): `:redact-fn` is the PROJECTION-SIDE-ONLY advanced override.
  Epoch records are causal replay material (per EP-0010); mutating them at
  rest would corrupt the replay contract, so storage stays raw. This
  helper runs ONLY at the off-box egress boundary,
  inside `re-frame.epoch.tool-pair/projected-record`, AFTER the
  frame/profile `re-frame.projection/project-egress` projection. The ring
  buffer + every `register-epoch-listener!` listener deliver the RAW
  record so on-box devtools (Xray diff, REPL, `restore-epoch!`) reason
  about exact state.

  The fn is the rare advanced escape for material the declaration-driven
  projection cannot prove (a sensitive slot no frame / schema declaration
  covers); the ordinary case needs only the frame's `:sensitive` / `:large`
  classification (EP-0015 §3) plus the per-slot schema props machine /
  resource data carry, which `project-egress` already applies. Because it runs
  on the projected egress COPY (the off-box record), it cannot affect
  `restore-epoch!` fidelity — that hazard the §15 'may affect restore
  fidelity' warning flagged is gone by construction.

  Per Tool-Pair §Time-travel §Redaction hook (spec/Tool-Pair.md:101):
  the warning MUST carry both `:frame` and `:rf.epoch/id` so a tool can
  correlate the failure to the specific projected record that fell back.

  Caller MUST wrap invocations in `(when interop/debug-enabled? ...)`
  — the whole epoch surface shares that gate; this helper carries no
  separate production gate. Cost when no fn is installed is a single
  keyword lookup on the config map and an identity return."
  [record]
  (if-let [f (state/redact-fn)]
    (if (some? record)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) e
          ;; Failure isolation: emit the warning, fall back to the
          ;; projected record. The keyword literal sits inside an
          ;; `(when interop/debug-enabled? ...)` gate at the call site
          ;; (the projection helper is itself gated), so Closure DCE
          ;; elides the warning emit + literals under :advanced +
          ;; goog.DEBUG=false.
          ;; Identity tag is the canonical qualified `:rf.epoch/id` (rf2-ifdsar):
          ;; the trace-tag layer spells the epoch id `:rf.epoch/id` everywhere.
          ;; The bare `:epoch-id` is the RECORD-field spelling we read FROM
          ;; (`(:epoch-id record)`); it stays bare as part of the deliberate
          ;; record/projection vocabulary — see Conventions §Trace/epoch identity.
          (trace/emit! :warning :rf.warning/epoch-redact-fn-exception
                       {:frame       (:frame record)
                        :rf.epoch/id (:epoch-id record)
                        :ex-msg      #?(:clj (.getMessage ^Throwable e)
                                        :cljs (.-message e))})
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
  true` stamp. Per privacy/sensitive? — the trace surface hoists the
  boolean from handler-scope-meta to the event top level (rf2-isdwf)."
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
  may be nil on halted paths (rf2-v0jwt :halted-destroy)."
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

  HOT PATH: fires once per dequeued event (rf2-nj6p7). `sensitive-paths-for` derefs
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

;; ---- redacted-modified-paths-count (rf2-dl3gx) ---------------------------
;;
;; Per Security.md §Epoch privacy posture and rf2-dl3gx (follow-on to
;; rf2-bz1cl's Xray-side heuristic chip): the record carries a
;; top-level integer count of frame-declared sensitive paths
;; (`[:rf.runtime/elision :sensitive-declarations]`) whose value differs between
;; `:db-before` and `:db-after`. Computed inside `build-record` from RAW
;; values BEFORE the installed `:redact-fn` runs — parallel to the
;; `:rf.epoch/sensitive?` rollup pattern just above.
;;
;; ## Why the count exists
;;
;; When an app `:redact-fn` substitutes the `:rf/redacted` sentinel into
;; both `:db-before` and `:db-after` at the same path (the standard
;; pattern for keeping sensitive material out of recorded records), and
;; the underlying value at that path actually changed across the
;; cascade, the structural diff sees `:rf/redacted` on both sides and
;; (correctly per the elision contract) emits no row. The developer
;; sees an empty diff body and no signal that anything happened.
;;
;; The count closes that gap by recording, on the raw record, exactly
;; how many frame-declared sensitive paths mutated this cascade.
;; Downstream consumers (Xray's `redacted-paths-modified` chip per
;; `tools/xray/spec/004-App-DB-Diff.md`, MCP wire pipeline, story
;; recorders) read the integer directly rather than re-deriving from
;; the post-redaction shape (which is heuristic — see the Xray helper
;; for the heuristic's tightness and approximation notes).
;;
;; ## What counts
;;
;; A path P counts when:
;;   1. P appears in `sensitive-paths-for frame-id`
;;      (declared via the commit-plane `:sensitive` effect a handler
;;      returns — EP-0025; the app-db elision registry
;;      `[:rf.runtime/elision :sensitive-declarations]` is written by
;;      those effects, not by a frame `:sensitive {:app-db …}` annotation
;;      and not from schema `{:sensitive? true}` props).
;;   2. `(not= (get-in db-before P) (get-in db-after P))`.
;;
;; The check uses **value-equality** on the raw (pre-redact-fn) dbs.
;; Pointer-equality would over-count under `assoc-in` rewrites that
;; produce a fresh subtree whose leaves are value-equal; `=` is the
;; precise predicate for "did this leaf actually change."
;;
;; ## What this does NOT cover
;;
;; - `:redact-fn` substitutions at NON-frame-declared paths. The
;;   redact-fn is opaque (record-in, record-out); the framework cannot
;;   inspect "would the fn redact this leaf?" without running it. The
;;   frame-declared sensitive-paths set is the framework's authoritative
;;   "what would be redacted" oracle — apps that install a redact-fn
;;   pointing at non-declared paths get the frame-declared count, not a
;;   superset. (In practice apps either declare sensitive paths on the
;;   frame or run a redact-fn covering the same paths; the two surfaces
;;   compose at the frame-declaration site.)
;; - Paths nominated as sensitive but with no live leaf (`nil` on both
;;   sides) — `(= nil nil)` is true, so the path doesn't count. A
;;   transition from non-nil to nil (or vice versa) counts as a value
;;   change.
;; - Production builds. The entire epoch surface elides under
;;   `interop/debug-enabled?` false; the count is dev-only by
;;   construction.

(defn redacted-modified-paths-count
  "Compute the record-level `:rf.epoch/redacted-modified-paths-count`
  rollup for the assembled record. Returns the integer count of
  frame-declared sensitive paths whose value differs between
  `db-before` and `db-after` (per rf2-dl3gx).

  HOT PATH: fires once per dequeued event (rf2-nj6p7). `sensitive-paths-for` derefs
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
  `:halted-destroy` path (per rf2-v0jwt). `(get-in nil P)` is `nil`;
  the predicate `(not= a b)` handles the nil/non-nil edge correctly
  (counts as a change when one side is nil and the other isn't)."
  [frame-id db-before db-after]
  (let [sensitive-paths (sensitive-paths-for frame-id)]
    (if (empty? sensitive-paths)
      0
      (reduce
        (fn [acc path]
          (if (not= (get-in db-before path)
                    (get-in db-after  path))
            (inc acc)
            acc))
        0
        sensitive-paths))))

;; ---- record assembly ------------------------------------------------------

(defn build-record
  "Assemble a `:rf/epoch-record`. `committed-at` is the record's durable
  causal time — per EP-0010 §Time (epoch record causal time) and Spec 002
  §The World-Input Rule it MUST be the committing causal token's
  `:rf.cofx` `:rf/time-ms`, threaded down from the router's settle /
  halt seam, NOT an ambient host-clock read at assembly time. Threading
  the token's `:rf/time-ms` makes `:committed-at` replayable: replaying the
  same event log with the same supplied `:rf/time-ms` values produces records
  with equal `:committed-at`, and a restored frame-state's recorded
  timestamps are not silently reinterpreted against a new ambient clock.

  `build-record` is a pure data builder — it performs NO clock read of its
  own. The two-arg-fewer arity defaults `outcome` to `:ok` and `halt-reason`
  to nil; both arities require `committed-at` (callers without a token — the
  pair-tool synthetic db-replace injections, which run with no application
  event in flight — pass `(interop/epoch-now-ms)` explicitly at the call
  site, where the ambient wall-clock read is the honest answer for a tool
  action, per EP-0010 §Time `Ambient time remains allowed for ...`. It is
  the wall-clock surface, NOT `interop/now-ms` — `:committed-at` is a
  durable field, kept wall-clock-class for cross-epoch comparison;
  rf2-czwwf4)."
  ([frame-id frame-state-before frame-state-after events committed-at]
   (build-record frame-id frame-state-before frame-state-after events committed-at :ok nil))
  ([frame-id frame-state-before frame-state-after events committed-at outcome halt-reason]
   ;; EP-0001 (rf2-3aizt1, decision #2): the CANONICAL snapshot unit is the
   ;; whole frame-state (`{:rf.db/app … :rf.db/runtime …}`) — both partitions.
   ;; `restore-epoch!` rewinds to `:frame-state-after`, reviving machines /
   ;; routes / elision / SSR runtime-db state, not just app-db. The
   ;; `:db-before` / `:db-after` slots are kept as the OPTIONAL app-db
   ;; PROJECTION (`(:rf.db/app frame-state-…)`) so pair tools can render
   ;; app-db diffs cheaply without re-projecting (Spec-Schemas
   ;; §`:rf/epoch-record`). The two `db-*` projections are also what the
   ;; sensitive-path rollup + redacted-modified count read — frame-declared
   ;; sensitive declarations target app-db paths (EP-0015 §3/§8), so the rollup
   ;; reasons over the app-db projection, not the whole frame-state.
   ;;
   ;; Per rf2-v0jwt §Outcomes — :outcome is required and pins the
   ;; drain-boundary outcome. The reference runtime commits one of three:
   ;; :ok / :halted-depth / :halted-destroy. (:halted-handler-exception
   ;; is a schema-reserved value the runtime never emits — handler
   ;; exceptions ride the interceptor error-capture seam and the drain
   ;; settles :ok with the error trace under :trace-events; see
   ;; Spec-Schemas §:rf/epoch-record §Outcomes.) :halt-reason is a
   ;; structured descriptor populated on halt paths, absent on :ok. The
   ;; schema in Spec-Schemas §:rf/epoch-record is the canonical pin.
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
   (let [;; App-db projection of the canonical frame-state — the `:db-before`
         ;; / `:db-after` slots + the sensitive-rollup signal. `frame-state`
         ;; may be nil on a halted-destroy path whose pre-cascade snapshot is
         ;; absent; `(:rf.db/app nil)` is nil, which consumers already tolerate.
         db-before (get frame-state-before frame/app-partition-key)
         db-after  (get frame-state-after  frame/app-partition-key)
         {:keys [event-id event dispatch-id fx-overrides interceptor-overrides]
          trigger-cofx :rf.cofx}
         (capture/find-trigger-event events)
         ;; Per rf2-ecu37: one fused walk producing all three
         ;; projections, replacing three independent transducer
         ;; passes over the same buffer. Mirrors `find-trigger-event`'s
         ;; rf2-txrq9 single-walk pattern.
         {:keys [sub-runs renders effects]} (capture/project-all events)
         ;; Per rf2-mrsck and Security.md §Epoch privacy posture:
         ;; the record-level boolean rollup mirrors the trace-event
         ;; `:sensitive?` stamp (rf2-isdwf) so listener consumers and
         ;; the projected-record helper branch on one slot per record.
         ;; Sensitive declarations target app-db paths, so it reasons over
         ;; the app-db projection (`db-before` / `db-after`).
         sensitive? (sensitive-rollup frame-id db-before db-after events)
         ;; Per rf2-dl3gx and Security.md §Epoch privacy posture: the
         ;; record-level integer counter of frame-declared sensitive
         ;; paths whose value differs between :db-before / :db-after.
         ;; Closes Xray's "redact-fn ⇒ empty diff but something changed"
         ;; gap by surfacing the suppressed signal directly on the
         ;; record. Computed BEFORE :redact-fn runs (parallel to the
         ;; :rf.epoch/sensitive? rollup above).
         redacted-modified-count (redacted-modified-paths-count
                                   frame-id db-before db-after)]
     (cond-> {:epoch-id           (state/next-epoch-id)
              :frame              frame-id
              ;; EP-0010 §Time (epoch record causal time) + Spec 002 §The
              ;; World-Input Rule (rf2-bh56rc): the durable causal-time fact
              ;; is the committing token's `:rf.cofx` `:rf/time-ms`,
              ;; threaded in via `committed-at` — NOT an ambient
              ;; `interop/now-ms` read at assembly time. The one `now-ms`
              ;; read happens ONCE at the causal boundary (envelope
              ;; construction, `re-frame.router/build-envelope`); it is not
              ;; re-read in the commit path. This makes the record replayable:
              ;; the same token replays to the same `:committed-at`.
              :committed-at       committed-at
              ;; CANONICAL (decision #2): the whole frame-state both before
              ;; and after — restore rewinds to `:frame-state-after`.
              :frame-state-before frame-state-before
              :frame-state-after  frame-state-after
              ;; OPTIONAL app-db projection — cheap tool diffs.
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
              :rf.epoch/redacted-modified-paths-count redacted-modified-count
              :trace-events       events
              :sub-runs           sub-runs
              :renders            renders
              :effects            effects}
       event-id    (assoc :event-id event-id)
       event       (assoc :trigger-event event)
       ;; Per rf2-1xdotm — pin the POST-GENERATION flat `:rf.cofx` replay
       ;; token (the causal cofx as it was after the router's declared-only
       ;; delivery ran: every generator-backed recordable fact minted at
       ;; processing-start written back into the in-flight `:rf.cofx`, plus
       ;; the framework `:rf/time-ms`). It is the slot a Tool-Pair replay
       ;; supplies alongside `:rf.cofx/mint-policy :strict` to re-present the
       ;; EXACT facts the original run consumed (EP-0017 §Recordable coeffects
       ;; + Tool-Pair §Replay-mint-policy). Spelled bare per the record-layer
       ;; vocabulary (Spec-Schemas §`:rf/epoch-record`). Conditional `cond->`
       ;; (rf2-kl5p1 shape): a halt path with no `:event/run-start` buffered,
       ;; or a production build whose dev-only run-start cofx tag is elided,
       ;; omits the slot — which the open-map schema admits. The marks
       ;; chokepoint redacts declared-sensitive recordable facts at the emit
       ;; site, so the value pinned here is already projection-safe.
       trigger-cofx (assoc :rf.cofx trigger-cofx)
       ;; Per rf2-rly4a — pin the settling cascade's `:dispatch-id` as a
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
       ;; Per rf2-yigokd — pin the envelope's serializable per-call + lexical
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
