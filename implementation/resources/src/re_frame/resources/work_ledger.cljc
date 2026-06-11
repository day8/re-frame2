(ns re-frame.resources.work-ledger
  "The resource-owned frame WORK LEDGER substrate (rf2-afpdkn, EP-0003
  slice 3). Per Spec 016 §Frame work ledger and §Ledger row retention and
  identity, and the `:rf.runtime/work-ledger` runtime-subsystem grading
  in [Runtime-Subsystems].

  Resource cache entries are cached read-model FACTS; in-flight attempts
  are WORK FACTS. They are linked (the entry points at its `:current-work`
  id; the ledger record carries the same id and the linked
  `:resource/key`) but never collapsed into one map.

  ## Two halves: serializable records + host side tables

  - **Serializable work records** live at `[:rf.runtime/work-ledger
    <work-id>]` inside the runtime-db partition (`:rf.db/runtime`). They
    are plain EDN — status, owners, causes, attempts, deadlines, outcomes
    — with NO host handles, so they ride SSR / hydration / epoch
    snapshots / Xray projection cleanly.
  - **Host handles** (the actual abortable request handles — AbortControllers
    / timeout handles / transport promises) live in a module-level side
    table keyed by `[frame-id work-id]`, OUTSIDE durable frame-state, and
    are NEVER serialized. This mirrors the host-side generation allocator
    (`re-frame.resources.state/generation-cache`) and routing's
    nav-counters / scroll caches (rf2-oosjmh / rf2-1hncp2): a transient
    host cache an epoch restore cannot rewind, released on frame destroy.

  ## The correctness split (Spec 016 §Cancellation is opportunistic;
  ## stale suppression is mandatory)

  - **Owner release updates ledger rows** (durable facts move forward).
  - **Abort is OPPORTUNISTIC** — a best-effort cancel of the host handle
    when one exists and can be cancelled; correctness NEVER rests on the
    cancel landing.
  - **Stale suppression by work-id + generation is MANDATORY** — a late
    reply carrying a superseded work-id / generation MUST NEVER overwrite
    a newer entry (enforced on the ENTRY in `re-frame.resources.events`
    via `live-entry-for-reply`; the ledger row mirrors the outcome).

  ## Retention (Spec 016 §Ledger row retention and identity)

  Terminal rows (`:completed` / `:failed` / `:timed-out` / `:suppressed`
  / `:cancelled`) are pruned on the linked entry's next successful
  transition; a small bounded per-resource-key tail is retained for
  Xray's recent-races view. Only NON-terminal rows' summaries ride the
  hydration / epoch wire. ONE identity per work record — stale
  suppression keys on `:work/id` (which embeds the generation); there is
  no separate `:stale-key` synonym.

  ## Single-writer (v1)

  In the HTTP-only MVP the ledger is written ONLY through the resource
  event handlers (which mint `:rf/framework-authority? true`). The ledger
  is DESIGNED multi-writer (later slices extend it to timers / streams /
  route loaders / spawned actors / machine async work), but who mints
  authority for each additional writer is an OPEN question deferred to the
  first non-resource writer (Spec 016 §Open questions). This slice keeps
  it single-writer / resource-owned."
  (:require [re-frame.resources.state :as state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- work-id (Spec 016 §Frame work ledger) --------------------------------
;;
;; The work-id embeds the generation: `[:rf.work/resource <scoped-key>
;; <generation>]`. Stale suppression keys on it (ONE identity per work
;; record). The generation allocator is monotonic + host-side
;; (`re-frame.resources.state`), so a work-id can never be re-issued across
;; an epoch restore — a dangling restored row can never re-match a live
;; entry (Spec 016 §Restore and replay part 1).

(def work-kind-resource
  "The `:work/kind` for a resource-owned attempt (`:resource`). The ledger
  is named neutrally — later slices add `:timer` / `:stream` / `:route` /
  `:actor` kinds; this slice writes only `:resource` work. Per Spec 016
  §Frame work ledger."
  :resource)

(defn resource-work-id
  "Build the resource work-id `[:rf.work/resource <scoped-key>
  <generation>]` — the ledger record key, the entry's `:current-work`
  pointer, and the FRAME-LOCAL identity stale suppression keys on. Embeds
  the generation so stale suppression keys on it. Per Spec 016 §Frame work
  ledger / §Ledger row retention and identity.

  Note: the work-id is frame-LOCAL — its `<scoped-key>` + `<generation>`
  carry no frame identity, so two frames issuing the same resource at the
  same generation mint the SAME work-id. The work-id is therefore NOT a
  safe process-global transport correlation token: the managed-HTTP
  in-flight registry keys by `:request-id` PROCESS-GLOBALLY and supersedes
  by equal request-id (Spec 014), so a bare-work-id request-id would let
  frame B supersede / abort frame A's in-flight transport request. The
  transport correlation token is the frame-QUALIFIED `managed-request-id`
  below — a deliberate second identity (Spec 016 §Ledger row retention and
  identity)."
  [scoped-key generation]
  [:rf.work/resource scoped-key generation])

(defn managed-request-id
  "Build the frame-QUALIFIED managed-HTTP transport correlation token
  `[:rf.req <frame-id> <work-id>]` — the `:request-id` the resource /
  mutation runtime hands the managed-HTTP transport (Spec 014). It is a
  DELIBERATE SECOND IDENTITY, distinct from the frame-local `:work/id`
  (Spec 016 §Ledger row retention and identity — \"if a future transport
  genuinely needs a transport-facing suppression token distinct from the
  internal work-id, it must be justified as a deliberate second identity\").

  WHY a second identity: the managed-HTTP in-flight registry keys by
  `:request-id` PROCESS-GLOBALLY and supersedes / aborts by EQUAL
  request-id (Spec 014 §`:request-id`). The work-id is frame-LOCAL (two
  frames at the same generation collide), so using the bare work-id as the
  request-id lets two frames issuing the same resource / mutation supersede,
  abort, or suppress each other's in-flight transport request. Qualifying
  with the frame id makes the transport correlation token process-globally
  UNIQUE per frame, so frames settle independently. Stale suppression
  inside a frame still keys on the work-id + generation (the durable
  identity); this token governs only transport-level in-flight correlation.
  Per Spec 016 §Transport / §Frame work ledger."
  [frame-id work-id]
  [:rf.req frame-id work-id])

;; ---- non-terminal / terminal status sets (Spec 016 §Ledger row retention) -

(def non-terminal-statuses
  "The non-terminal work statuses an attempt moves through while it is
  still live. ONLY these rows' summaries ride the hydration / epoch /
  restore wire (terminal rows are local Xray history). Per Spec 016
  §Ledger row retention and identity / [Runtime-Subsystems] clause 4."
  #{:queued :running :abort-requested})

(def terminal-statuses
  "The terminal work statuses an attempt may reach with an outcome
  summary. Terminal rows are pruned on the linked entry's next successful
  transition; a bounded per-resource-key tail is retained for Xray. Per
  Spec 016 §Ledger row retention and identity. (Mirrors
  `re-frame.resources.state/terminal-work-statuses`; re-exported here as
  the ledger surface's own name so a ledger consumer reads one home.)"
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn terminal?
  "True iff `status` is a terminal work-ledger status (the attempt has
  settled with an outcome). Per Spec 016 §Ledger row retention and
  identity."
  [status]
  (contains? terminal-statuses status))

;; ---- serializable work record (Spec 016 §Frame work ledger) ---------------
;;
;; Plain EDN — NO host handles. The host handles (AbortController / timeout
;; handle / promise) live in the side table keyed by `[frame-id work-id]`.

(defn work-record
  "Construct a serializable `:running` work record for a fresh attempt. The
  record carries the work identity (`:work/id` / `:work/kind` /
  `:work/frame`), the linked `:resource/key` + `:generation`, the
  `:transport`, the live `:owners` / `:causes`, whether the attempt is
  `:cancellable?` (a best-effort hint — abort is opportunistic), and the
  `:started-at` / `:deadline-at` timestamps. NO host handles ride this
  shape (they are in the side table). Per Spec 016 §Frame work ledger.

  Opts:
  - `:work-id`     — `[:rf.work/resource <scoped-key> <generation>]`
  - `:frame-id`    — the qualified `:work/frame` stamp (matches the
                     reply's `:rf.frame/id`)
  - `:resource-key`/`:generation`/`:transport`
  - `:owner`       — the initiating owner (nil-safe; folded into `:owners`)
  - `:cause`       — the initiating cause (nil-safe; folded into `:causes`)
  - `:cancellable?`— best-effort cancel hint (default true)
  - `:started-at`  — epoch-ms
  - `:deadline-at` — epoch-ms or nil"
  [{:keys [work-id frame-id resource-key generation transport
           owner cause cancellable? started-at deadline-at]}]
  {:work/id      work-id
   :work/kind    work-kind-resource
   :work/frame   frame-id
   :resource/key resource-key
   :generation   generation
   :transport    transport
   :status       :running
   :owners       (if owner #{owner} #{})
   :causes       (if cause [cause] [])
   :cancellable? (if (some? cancellable?) cancellable? true)
   :started-at   started-at
   :deadline-at  deadline-at})

(defn join-owner+cause
  "Dedupe-join a SUPPLEMENTARY ensure onto an existing non-terminal work
  record: attach `owner` to `:owners` and append `cause` to `:causes` (no
  status change — the in-flight attempt is shared). Per Spec 016 §Race
  (ensure while in flight joins the existing current work record). Nil-safe
  for both."
  [record owner cause]
  (cond-> record
    owner (update :owners (fnil conj #{}) owner)
    cause (update :causes (fnil conj []) cause)))

(defn release-owner-from-record
  "Drop `owner` from a work record's `:owners`. Used by owner release /
  scope clear / route supersession when a lease exits. Does NOT change the
  record status — the attempt stays live for any remaining owner; whether
  it is then aborted is the opportunistic-abort decision (only when NO
  owner remains). Per Spec 016 §Race (owner release while in flight aborts
  only when no remaining owner needs that work record)."
  [record owner]
  (update record :owners (fnil disj #{}) owner))

(defn mark-terminal
  "Settle a work record to a terminal `status` (`:completed` / `:failed` /
  `:timed-out` / `:suppressed` / `:cancelled`) with an `outcome` summary.
  Per Spec 016 §Ledger row retention and identity — the row is then prunable
  on the linked entry's next successful transition. `outcome` is a small
  serializable summary (NOT raw data — Xray gets summaries, the projection
  boundary)."
  [record status outcome]
  (assoc record :status status :outcome outcome))

(defn mark-abort-requested
  "Move a record to `:abort-requested` (an opportunistic cancel was issued
  for its host handle, but the attempt has not yet terminated). NON-terminal
  — the record stays on the wire until the transport actually settles it
  (or a stale reply is suppressed). Per Spec 016 §Frame work ledger (the
  `:abort-requested` non-terminal status)."
  [record]
  (assoc record :status :abort-requested))

(defn serializable-record?
  "True iff `record` is host-handle-free serializable EDN — the durable
  invariant a work record MUST satisfy (it rides SSR / hydration / epoch
  snapshots / Xray projection). Host handles (AbortControllers / promises /
  timer handles) live ONLY in the side table, never on the record. Per Spec
  016 §Frame work ledger / [Runtime-Subsystems] clause 4. Reuses the same
  serializable-EDN walker the cache-key boundary uses."
  [record]
  (state/serializable-edn? record))

;; ---- runtime-db record bookkeeping (Spec 016 §Cache home) -----------------
;;
;; Pure `(runtime-db, …) -> runtime-db` helpers over the
;; `[:rf.runtime/work-ledger <work-id>]` subtree. The resource event
;; handlers call these alongside the entry transitions so the ledger row
;; and the linked entry move together.

(defn record-path
  "Runtime-db-relative path to a single work record by its `:work/id`. Per
  Spec 016 §Cache home (`[:rf.runtime/work-ledger <work-id>]`)."
  [work-id]
  [:rf.runtime/work-ledger work-id])

(defn put-record
  "Write `record` at `[:rf.runtime/work-ledger <work-id>]` in `runtime-db`.
  Returns the updated runtime-db."
  [runtime-db work-id record]
  (assoc-in runtime-db (record-path work-id) record))

(defn get-record
  "Read the work record under `work-id` from `runtime-db`, or nil."
  [runtime-db work-id]
  (get-in runtime-db (record-path work-id)))

(defn update-record
  "Apply `f` (and `args`) to the work record under `work-id` in
  `runtime-db`, writing the result back. No-op when no record exists (a
  reply for an already-pruned row). Returns the updated runtime-db."
  [runtime-db work-id f & args]
  (if (get-record runtime-db work-id)
    (apply update-in runtime-db (record-path work-id) f args)
    runtime-db))

(defn prune-record
  "Remove the work record under `work-id` from `runtime-db`. Used when a
  terminal row is pruned on the linked entry's next successful transition
  (Spec 016 §Ledger row retention and identity). Returns the updated
  runtime-db."
  [runtime-db work-id]
  (update runtime-db :rf.runtime/work-ledger dissoc work-id))

(def default-terminal-tail
  "How many terminal work rows to retain per resource-key for Xray's
  recent-races view after a prune (Spec 016 §Ledger row retention and
  identity — \"a small bounded per-resource-key tail\"). Small + bounded:
  the ledger rides SSR / hydration / every epoch snapshot, so unbounded
  terminal-row growth is worse than trace growth."
  3)

(defn prune-terminal-for-key
  "Prune every TERMINAL work record linked to `resource-key` from the
  ledger, retaining at most `keep-tail` of the most-recently-started ones
  for Xray's recent-races view (Spec 016 §Ledger row retention and
  identity — \"a small bounded per-resource-key tail\"). Non-terminal rows
  for the key are NEVER pruned. Called on the linked entry's next
  successful transition. Returns the updated runtime-db.

  `keep-tail` defaults to `default-terminal-tail`."
  ([runtime-db resource-key] (prune-terminal-for-key runtime-db resource-key default-terminal-tail))
  ([runtime-db resource-key keep-tail]
   (let [ledger (:rf.runtime/work-ledger runtime-db)
         ;; terminal rows for this key, newest-first by :started-at
         terminal-for-key
         (->> ledger
              (filter (fn [[_ r]] (and (= resource-key (:resource/key r))
                                       (terminal? (:status r)))))
              (sort-by (fn [[_ r]] (or (:started-at r) 0)) >))
         drop-ids (->> terminal-for-key (drop keep-tail) (map key))]
     (if (seq drop-ids)
       (update runtime-db :rf.runtime/work-ledger
               (fn [l] (reduce dissoc l drop-ids)))
       runtime-db))))

;; ---- host-side handle side table (Spec 016 §Frame work ledger) ------------
;;
;; The non-serializable cancellation / timer handles keyed by `[frame-id
;; work-id]`. A module-level transient host cache — NOT runtime-db, NOT
;; serialized, off the epoch / SSR egress wire. Mirrors the host-side
;; generation allocator (`state/generation-cache`) and routing's
;; nav-counters / scroll caches (rf2-oosjmh / rf2-1hncp2). Cleared on frame
;; destroy (Spec 016 [Runtime-Subsystems] clause 5: transient host handles
;; dropped).

(defonce
  ^{:doc "Host-side side table of NON-serializable work handles, keyed by
   `[frame-id work-id]` → a host-handle map (e.g.
   `{:abort-fn <thunk> :transport :rf.http/managed}`). Transient host
   state (NOT runtime-db), so an epoch restore cannot rewind / recycle it,
   and it never rides the SSR / hydration / epoch wire — the durable
   record (the serializable row in runtime-db) is the only thing that
   serializes. Cleared per-frame on frame destroy
   (`release-frame!`). Per Spec 016 §Frame work ledger / [Runtime-Subsystems]
   clause 5."}
  handle-table
  (atom {}))

(defn put-handle!
  "Record a host handle for `[frame-id work-id]` in the side table. `handle`
  is a host-handle map carrying whatever abort capability the transport
  exposes (e.g. `{:abort-fn <thunk> :transport :rf.http/managed}`). Returns
  nil. Per Spec 016 §Frame work ledger (host handles live OUTSIDE durable
  frame-state, keyed by frame id + work id)."
  [frame-id work-id handle]
  (swap! handle-table assoc [frame-id work-id] handle)
  nil)

(defn get-handle
  "Read the host handle for `[frame-id work-id]` from the side table, or
  nil (when absent — already cleared, or a transport that records none)."
  [frame-id work-id]
  (get @handle-table [frame-id work-id]))

(defn clear-handle!
  "Drop the host handle for `[frame-id work-id]` from the side table
  (without firing its abort — the caller decides whether to abort). Called
  when a work record terminates so a settled attempt's handle does not leak.
  Idempotent. Returns nil."
  [frame-id work-id]
  (swap! handle-table dissoc [frame-id work-id])
  nil)

(defn opportunistic-abort!
  "BEST-EFFORT cancel the host handle for `[frame-id work-id]`, then drop it
  from the side table. Per Spec 016 §Cancellation is opportunistic; stale
  suppression is mandatory: this MAY abort the in-flight request if the host
  handle exists and can be cancelled; if it cannot, correctness still rests
  on the work-id + generation stale-suppression check (NOT on the cancel
  landing). Returns true iff an abort thunk was found and fired (a hint for
  the caller's trace), false otherwise. Never throws (a throwing abort-fn is
  swallowed — a failed cancel must not strand the teardown)."
  [frame-id work-id]
  (let [handle (get-handle frame-id work-id)
        aborted? (boolean (when-let [abort-fn (:abort-fn handle)]
                            (try (abort-fn :resource-superseded) true
                                 (catch #?(:clj Throwable :cljs :default) _ false))))]
    (clear-handle! frame-id work-id)
    aborted?))

;; ---- opportunistic abort via the transport fx (transport-neutral) ---------
;;
;; The resource runtime does NOT itself hold the AbortController — the
;; managed-HTTP transport owns it, keyed (host-side) by the request-id
;; (the frame-QUALIFIED `managed-request-id`, NOT the bare work-id — see the
;; collision note on `managed-request-id`). The transport-neutral
;; opportunistic-abort path is therefore an fx the runtime emits, late-bound
;; so resources never statically depends on the HTTP transport. The
;; side-table `:abort-fn` above is the slot a FUTURE transport that hands the
;; runtime a direct handle would fill; for managed HTTP the abort rides the
;; `:rf.http/managed-abort` fx keyed by the frame-qualified request-id.

(def managed-abort-fx
  "The reserved managed-HTTP abort fx-id (`:rf.http/managed-abort`, Spec
  014) — aborts an in-flight managed request by its `:request-id`. The
  resource / mutation runtime's transport request-id is the frame-QUALIFIED
  `managed-request-id` (`[:rf.req <frame-id> <work-id>]`), so a best-effort
  abort of a superseded attempt emits
  `[:rf.http/managed-abort [:rf.req <frame-id> <work-id>]]` — the SAME token
  the lower registered, so it actually resolves the right frame's in-flight
  request (a bare work-id would miss it, or hit a colliding sibling frame).
  Opportunistic — a no-op when the transport is no longer holding the
  request (the reply already landed, or the artefact is absent). Per Spec
  016 §Cancellation is opportunistic."
  :rf.http/managed-abort)

(def managed-abort-fx-transport
  "The transport id whose opportunistic abort rides `:rf.http/managed-abort`
  (managed HTTP). Per Spec 016 §Transport (the single initial-scope
  transport)."
  :rf.http/managed)

(defn abort-fx
  "Build the best-effort transport-abort fx pair for a superseded /
  released attempt, or nil when no abort is possible. For managed HTTP this
  is `[:rf.http/managed-abort [:rf.req <frame-id> <work-id>]]` — abort by
  the frame-QUALIFIED `managed-request-id`, the SAME token the lower
  registered. Opportunistic — the transport no-ops when it is no longer
  holding the request. Returns nil for an unknown transport (no abort
  capability — stale suppression alone protects correctness).

  The request-id MUST be frame-qualified to match the registered token:
  the managed-HTTP in-flight registry keys by request-id process-globally
  (Spec 014), so a bare work-id would either miss the abort entirely (the
  registered token is qualified) or, across frames, resolve a sibling
  frame's colliding request. `frame-id` is the issuing frame (the
  `:work/frame` stamp on the ledger record). Per Spec 016 §Cancellation is
  opportunistic; stale suppression is mandatory / §Transport."
  [transport frame-id work-id]
  (when (= transport managed-abort-fx-transport)
    [managed-abort-fx (managed-request-id frame-id work-id)]))

;; ---- side-table write fx (host-side; mirrors commit-generation) -----------
;;
;; The work-handle side table is host-side transient state, so its WRITES
;; ride fx (not a runtime-db effect) — exactly as the host-side generation
;; high-water bump rides `:rf.resource/commit-generation`. The runtime
;; emits `:rf.resource/record-work-handle` alongside the transport lower,
;; and `:rf.resource/clear-work-handle` when an attempt is superseded /
;; settled. fx handlers are binary `(fn [ctx args] …)` (Spec 002).

(def record-work-handle-meta
  "Metadata for the `:rf.resource/record-work-handle` fx registration. The
  WRITE half of the host-side work-handle side table — records the host
  abort capability for `[frame-id work-id]` outside durable frame-state."
  {:doc "Record a work-ledger host handle in the host-side side table, keyed
by `[frame-id work-id]`. Args: `{:frame-id … :work-id … :transport …
:request-id …}`. The `:request-id` is the frame-QUALIFIED transport
correlation token (`managed-request-id`), NOT the bare work-id — it is the
token the managed-HTTP registry keys on, recorded so frame-destroy teardown
and Xray can correlate the in-flight transport request to the work. Emitted
alongside the transport lower; the WRITE counterpart cleared by
`:rf.resource/clear-work-handle` (and frame destroy). For managed HTTP the
transport owns the AbortController (keyed by the request-id), so no live
`:abort-fn` rides this slot — the opportunistic abort rides the
`:rf.http/managed-abort` fx; the slot records the correlation so frame-destroy
teardown can enumerate the frame's in-flight work. A future transport that
hands the runtime a direct handle fills the `:abort-fn` slot. Per Spec 016
§Frame work ledger."})

(defn record-work-handle-handler
  "`:rf.resource/record-work-handle` fx handler. Writes the host-side
  work-handle side-table slot for `[frame-id work-id]`. Args:
  `{:frame-id … :work-id … :transport … :request-id … :abort-fn …}`."
  [_ctx {:keys [frame-id work-id transport request-id abort-fn]}]
  (put-handle! frame-id work-id
               (cond-> {:transport transport :request-id request-id}
                 abort-fn (assoc :abort-fn abort-fn)))
  nil)

(def clear-work-handle-meta
  "Metadata for the `:rf.resource/clear-work-handle` fx registration. Drops
  a host-side work-handle side-table slot (without firing its abort — the
  caller decides; the transport abort rides `:rf.http/managed-abort`)."
  {:doc "Drop a work-ledger host handle from the host-side side table, keyed
by `[frame-id work-id]`. Args: `{:frame-id … :work-id …}`. Emitted when an
attempt is superseded / settled so a stale handle does not leak. Per Spec 016
§Frame work ledger."})

(defn clear-work-handle-handler
  "`:rf.resource/clear-work-handle` fx handler. Drops the host-side
  work-handle side-table slot for `[frame-id work-id]`. Args:
  `{:frame-id … :work-id …}`."
  [_ctx {:keys [frame-id work-id]}]
  (clear-handle! frame-id work-id)
  nil)

;; ---- frame teardown (Spec 016 [Runtime-Subsystems] clause 5) --------------
;;
;; On frame destroy, the TRANSIENT host handles for that frame are dropped
;; (best-effort aborted on the way out) — the durable ledger facts ride the
;; frame value and are released atomically when the frame value is dropped.
;; Wired off the single normative teardown boundary via the
;; `:resources/on-frame-destroyed!` late-bind hook the façade publishes (the
;; same shape as routing's `:routing/on-frame-destroyed!`). Frame destroy
;; ALSO drops the host-side generation high-water mark
;; (`state/release-frame!`), composed into the same hook body in the façade.

(defn release-frame!
  "Release a destroyed frame's TRANSIENT work-ledger host handles: drop
  every `[frame-id work-id]` slot for `frame-id` from the side table,
  best-effort aborting each on the way out (opportunistic — a throwing
  abort-fn is swallowed). The durable serializable records ride the frame
  value and are released atomically when the frame is dropped at destroy, so
  this hook touches ONLY the host side table. Idempotent — no-op on a frame
  with no handles. Per Spec 016 §Stale and GC scheduling (frame destroy
  cancels all resource timers / clears host handles for that frame) /
  [Runtime-Subsystems] clause 5. Returns nil."
  [frame-id]
  (let [slots (->> (keys @handle-table)
                   (filter (fn [[fid _]] (= fid frame-id))))]
    (doseq [[fid wid] slots]
      (opportunistic-abort! fid wid)))
  nil)

(defn reset-cache!
  "Drop EVERY frame's work-ledger host handles (test isolation). Published
  as a reset hook so the shared CLJS `make-reset-runtime-fixture`
  reset-hooks table clears it per test (host-side transient state, NOT
  cleared by the runtime / frames reset). Best-effort aborts each handle on
  the way out. Returns nil."
  []
  (doseq [[fid wid] (keys @handle-table)]
    (opportunistic-abort! fid wid))
  (reset! handle-table {})
  nil)

;; ---- frame-stamped teardown entry (carried-frame invariant) ---------------
;;
;; The teardown hook the core's `destroy-frame!` invokes is passed the
;; frame-id directly (it is the destroy target), so no `:rf.frame/id`
;; resolution is needed — mirror routing's `release-frame!` signature.

(defn on-frame-destroyed!
  "The `:resources/on-frame-destroyed!` teardown body for the work-ledger
  host handles. `destroy-frame!` invokes it by key with the destroyed
  `frame-id`. Releases the frame's transient work-ledger host handles
  (`release-frame!`). The façade composes this with
  `state/release-frame!` (the generation high-water mark) so both
  host-side transient caches drop on one hook. Per Spec 016
  [Runtime-Subsystems] clause 5."
  [frame-id]
  (release-frame! frame-id)
  nil)
