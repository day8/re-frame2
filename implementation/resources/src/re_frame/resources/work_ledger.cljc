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
  it single-writer / resource-owned.

  ## The durable reply-target boundary (rf2-6kdcs9, EP-0011 §Work Ledger
  ## Integration / Managed-Effects §Work-ledger integration)

  Managed-Effects §Work-ledger integration says a ledger row *is* the
  reified continuation, and its `:reply-to` field is the reply target made
  DURABLE — \"where the ledger row and the reply envelope visibly become one
  fact.\" The resource-read row's continuation is the FRAMEWORK-INTERNAL
  reply target (`:rf.resource.internal/succeeded` carrying the verification
  payload), which is DETERMINISTICALLY RECONSTRUCTABLE from the row's own
  durable facts (`:work/id` / `:resource/key` / `:generation` / `:work/frame`)
  — so the durable continuation is a DERIVED row fact, not a separately stored
  copy that could drift from the verification identity. `durable-reply-to`
  reconstructs it and runs it through `re-frame.reply/durable-target` so the
  reified continuation is asserted DATA-ONLY (no host handle, no ephemeral
  `::post` / `::stale-authority` slot) before it could ride a durable row —
  the same crispness the spec illustrates.

  An app-supplied CALL-SITE continuation (the mutation `:reply-to`, EP-0016
  D1) is a DIFFERENT continuation: a transport-payload-only app target fired
  once on an accepted reply, NOT a durable ledger row fact. It is hardened at
  its OWN boundary (the mutation execute handler runs it through
  `re-frame.reply/durable-target` before it rides the transport payload, so a
  malformed / host-handle target fails LOUD at issuance, before transport /
  trace). The two continuations are kept distinct: the ledger owns the
  framework-internal reified continuation; the call-site target is the app's
  transport-payload continuation."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.reply :as reply]
            [re-frame.resources.state :as state]))

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

(def work-id-id
  "The CEDN-1 BYTE-IDENTITY map-key for a `work-id`
  (`[:rf.work/resource <scoped-key> <generation>]`) — its `canonical-bytes`
  string (rf2-9e0tyq). The work-ledger runtime-db map is keyed on THIS string,
  NOT the work-id vector, for the SAME reason `:entries` is keyed on
  `state/key-id`: the work-id embeds the scoped-key, so two work-ids that
  differ only by a list-vs-vector params spelling are `=`-equal (Clojure
  `(= [1 2 3] '(1 2 3))`) and would collide in the ledger map even though
  their CEDN-1 byte identities differ. Keying on the bytes string makes the
  map-key comparison exactly the CEDN-1 identity. The work-id VECTOR remains
  the kind-preserving `:work/id` carried in the record, the entry's
  `:current-work`, and on the wire. Total on a work-id over a canonical
  scoped-key. Per Spec 016 §Frame work ledger.

  This IS `state/key-id` (both are `canonical-bytes` of an
  already-canonical EDN identity — rf2-366u0g): the work-id-as-map-key
  byte-identity rule is the SAME rule the scoped-key-as-map-key follows, so
  the ledger surface re-binds the one canonical wrapper under its own name
  rather than re-typing the `canonical-bytes` call."
  state/key-id)

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
  Spec 016 §Ledger row retention and identity. The canonical set lives in
  `re-frame.resources.state/terminal-work-statuses` (rf2-366u0g); re-bound
  under the ledger surface's own name so a ledger consumer reads one home."
  state/terminal-work-statuses)

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
  - `:resource/key`/`:generation`/`:transport`
  - `:owner`       — the initiating owner (nil-safe; folded into `:owners`)
  - `:cause`       — the initiating cause (nil-safe; folded into `:causes`)
  - `:cancellable?`— best-effort cancel hint (default true)
  - `:started-at`  — epoch-ms
  - `:deadline-at` — epoch-ms or nil
  - `:page-index`  — EP-0021 R1/R2: the 0-based PAGE the attempt is fetching for
                     an INFINITE feed (the durable \"page-fetch work evidence\"
                     R1 names). A page-0 fetch (a first ensure / a
                     window-preserving refetch's replacement page) is `0`; a
                     `:rf.resource/load-more` APPEND is the positive index past
                     the accumulated tail. Absent (nil) for an ordinary
                     (non-infinite) resource / mutation attempt. The
                     `:rf.resource/fetching-next?` sub reads it to distinguish a
                     load-more in flight (positive index) from a whole-feed
                     `:fetching?` refresh (page-0). Plain EDN — it rides SSR /
                     restore with the rest of the row."
  [{:keys [work-id frame-id generation transport
           owner cause cancellable? started-at deadline-at page-index]
    resource-key :resource/key}]
  (cond-> {:work/id      work-id
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
           :deadline-at  deadline-at}
    ;; EP-0021 — record the page index for an infinite-feed page fetch so
    ;; `:fetching-next?` is derivable from the durable row (a load-more =
    ;; a positive index; a page-0 fetch / refetch = 0). Omitted entirely for
    ;; a non-infinite attempt (page-index nil) — the row shape is unchanged
    ;; for every ordinary resource / mutation.
    (some? page-index) (assoc :page-index page-index)))

;; ---- durable reply-target (rf2-6kdcs9) ------------------------------------
;;
;; The framework-internal resource-read reply target made DURABLE
;; (Managed-Effects §Work-ledger integration — "the row's `:reply-to` field is
;; the reply target made durable"). The target is DERIVED from the durable row
;; facts (it is the verification payload the internal reply handlers gate on),
;; then asserted DATA-ONLY via `re-frame.reply/durable-target`.

(defn durable-reply-to
  "Reconstruct the DURABLE, data-only reply target for a resource-read work
  `record` — the reified continuation Managed-Effects §Work-ledger
  integration says the row carries as `:reply-to`. The target is DERIVED from
  the record's own durable facts (the verification payload the internal reply
  handler gates on: `:work/id` + `:resource/key` + `:generation` +
  `:work/frame`), so the durable continuation can never drift from the
  stale-suppression identity. Runs the reconstructed descriptor through
  `re-frame.reply/durable-target`, which strips any ephemeral slot and FAILS
  LOUD (`:rf.reply/non-data-target`) if a host handle hid in a public field —
  asserting the reified continuation is data-only before it could ride a
  durable row / SSR / hydration / epoch snapshot. Returns the data-only
  normalized descriptor. Per Spec 016 §Frame work ledger / Managed-Effects
  §Work-ledger integration.

  The verification payload carries exactly the stale-suppression identity the
  internal reply handler gates on — `:work/id` (the single suppression key,
  EP-0007), the linked `:resource/key`, the `:generation`, and the carried
  `:rf.frame/id` (the `:work/frame` stamp). Scope is NOT a suppression key (it
  is correlation metadata derivable from the resource key), so it is omitted
  from the durable continuation — one name per fact."
  [record]
  ;; rf2-7wecib — the success reply event id is inlined here as the normative
  ;; literal (Spec 016 §Events; the same id `transport.http/succeeded-reply`
  ;; spells). The ledger spells it locally rather than depending on
  ;; transport.http (a layering dodge, not a cycle) — and there is exactly ONE
  ;; use, so a local `def` only added a level of indirection over the literal.
  ;; The success leg is canonical: the failure leg
  ;; (`:rf.resource.internal/failed`) shares the SAME verification payload, so
  ;; one durable target represents the reified continuation.
  (reply/durable-target
    {:event [:rf.resource.internal/succeeded
             {:work/id      (:work/id record)
              :resource/key (:resource/key record)
              :generation   (:generation record)
              :rf.frame/id  (:work/frame record)}]}))

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
  "Runtime-db-relative path to a single work record, keyed on the CEDN-1 byte
  `work-id-id` (NOT the work-id vector; rf2-9e0tyq) so a list- and a
  vector-params work-id never collide in the ledger map. Per Spec 016
  §Cache home (`[:rf.runtime/work-ledger <work-id-id>]`)."
  [work-id]
  [:rf.runtime/work-ledger (work-id-id work-id)])

;; ---- resource-key → work-id inverse index (rf2-wyan7e) --------------------
;;
;; `prune-terminal-for-key` runs after EVERY resource settle to trim terminal
;; rows for the settling key. Filtering the WHOLE ledger to find that key's
;; rows is O(W) over all in-flight + terminal work across all resources, per
;; settle. This inverse index `{<rk-id> #{<work-id-id> …}}` (mirroring the
;; resource `:tag-index`) lets the prune visit ONLY the settling key's rows.
;;
;; It is a PURE derived projection of the ledger (a row contributes its
;; `work-id-id` to its `(:resource/key row)`'s bucket), so it is rebuilt — never
;; trusted — wherever the ledger is installed wholesale (hydration / restore):
;; `prune-terminal-for-key` self-heals by rebuilding the whole index when it
;; finds the ledger populated but the index absent (the post-hydration shape),
;; so no SSR / restore install site has to thread it. Maintained incrementally
;; on the live path by `put-record` (add) / `prune-record` + the prune (remove).

(def work-ledger-by-key-key
  "Reserved runtime-db key for the work-ledger's resource-key → work-id-id
  inverse index `{<rk-id> #{<work-id-id> …}}`. Recomputable-from-the-ledger
  (rebuilt on hydration / restore, never trusted from a snapshot — mirrors the
  resource `:tag-index`). Per Spec 016 §Ledger row retention (rf2-wyan7e)."
  :rf.runtime/work-ledger-by-key)

(defn recompute-ledger-index
  "Rebuild the `:rf.runtime/work-ledger-by-key` inverse index
  `{<rk-id> #{<work-id-id> …}}` from the ledger map in `runtime-db`. The single
  authoritative full rebuild the hydration / restore self-heal uses (a pure
  projection of the ledger). Returns the updated runtime-db."
  [runtime-db]
  (let [ledger (:rf.runtime/work-ledger runtime-db)
        idx    (reduce-kv
                 (fn [acc wid-id record]
                   (let [rk-id (state/key-id (:resource/key record))]
                     (update acc rk-id (fnil conj #{}) wid-id)))
                 {}
                 ledger)]
    (assoc runtime-db work-ledger-by-key-key idx)))

;; The incremental maintainers act ONLY when the index already EXISTS in the
;; runtime-db. A wholesale ledger install (epoch restore / SSR hydrate /
;; replace-frame-state! / router rollback / test fixtures) does NOT carry the
;; index (a derived projection never trusted from a snapshot), so leaving it
;; absent is the signal `prune-terminal-for-key` rebuilds it from ground truth
;; ONCE — a `put-record` that ran first must NOT half-build it and mask that
;; rebuild. Once present, every put/prune keeps it complete and in step.

(defn- index-present? [runtime-db] (contains? runtime-db work-ledger-by-key-key))

(defn- index-add
  "Add `work-id-id` to its `rk-id` bucket in the inverse index — ONLY when the
  index already exists (see the comment above). Pure."
  [runtime-db rk-id work-id-id*]
  (if (index-present? runtime-db)
    (update-in runtime-db [work-ledger-by-key-key rk-id] (fnil conj #{}) work-id-id*)
    runtime-db))

(defn- index-remove
  "Remove `work-id-id` from its `rk-id` bucket in the inverse index, dropping an
  emptied bucket entirely (the recompute shape never leaves empty buckets) —
  ONLY when the index already exists. Pure."
  [runtime-db rk-id work-id-id*]
  (if (index-present? runtime-db)
    (let [s (disj (get-in runtime-db [work-ledger-by-key-key rk-id] #{}) work-id-id*)]
      (if (seq s)
        (assoc-in runtime-db [work-ledger-by-key-key rk-id] s)
        (update runtime-db work-ledger-by-key-key dissoc rk-id)))
    runtime-db))

(defn put-record
  "Write `record` at the work-ledger byte-keyed slot for `work-id` in
  `runtime-db`, and register it in the resource-key inverse index when that
  index is live (rf2-wyan7e — the index member is idempotent, so re-putting an
  updated record is a no-op for the index). Returns the updated runtime-db."
  [runtime-db work-id record]
  (-> runtime-db
      (assoc-in (record-path work-id) record)
      (index-add (state/key-id (:resource/key record)) (work-id-id work-id))))

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
  "Remove the work record under `work-id` from `runtime-db`, and drop it from
  the resource-key inverse index (rf2-wyan7e). Used when a terminal row is
  pruned on the linked entry's next successful transition (Spec 016 §Ledger row
  retention and identity). No-op for the index when no record exists. Returns
  the updated runtime-db."
  [runtime-db work-id]
  (let [wid-id (work-id-id work-id)
        record (get-in runtime-db [:rf.runtime/work-ledger wid-id])]
    (cond-> (update runtime-db :rf.runtime/work-ledger dissoc wid-id)
      record (index-remove (state/key-id (:resource/key record)) wid-id))))

(defn update-record-by-id
  "Apply `f` (and `args`) to the work record under an ALREADY-COMPUTED byte
  `work-id-id` (a ledger map key from a `(map key)` ledger scan) in
  `runtime-db`, writing the result back (rf2-9e0tyq). No-op when no record
  exists. Distinct from `update-record` (which transforms a work-id VECTOR to
  its byte id first) — a caller iterating the ledger already holds the byte
  id and must NOT re-transform it. Returns the updated runtime-db."
  [runtime-db work-id-id f & args]
  (if (get-in runtime-db [:rf.runtime/work-ledger work-id-id])
    (apply update-in runtime-db [:rf.runtime/work-ledger work-id-id] f args)
    runtime-db))

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
   ;; rf2-wyan7e — self-heal: a wholesale-installed ledger (hydration /
   ;; restore) arrives with NO inverse index (it is a derived projection, never
   ;; trusted from the wire). Rebuild it ONCE here so the per-key visit below is
   ;; bounded; live operation keeps it in step via put-record / prune-record.
   ;; A ledger present but index absent is exactly the post-install shape.
   (let [ledger (:rf.runtime/work-ledger runtime-db)
         runtime-db (if (and (seq ledger)
                             (not (contains? runtime-db work-ledger-by-key-key)))
                      (recompute-ledger-index runtime-db)
                      runtime-db)
         ;; rf2-9e0tyq — match by CEDN-1 byte identity, not `=` over the
         ;; `:resource/key` vector (which would `=`-collapse a list- and a
         ;; vector-params key and prune both resources' rows).
         rk-id  (state/key-id resource-key)
         ;; rf2-wyan7e — visit ONLY this key's rows (via the inverse index)
         ;; instead of scanning the WHOLE ledger per settle (O(rows-for-key)
         ;; rather than O(all-work)). The index members ARE this key's row ids.
         ledger (:rf.runtime/work-ledger runtime-db)
         this-key-ids (get-in runtime-db [work-ledger-by-key-key rk-id])
         ;; terminal rows for this key, newest-first by :started-at
         terminal-for-key
         (->> this-key-ids
              (keep (fn [wid-id]
                      (let [r (get ledger wid-id)]
                        (when (and r (terminal? (:status r)))
                          [wid-id r]))))
              (sort-by (fn [[_ r]] (or (:started-at r) 0)) >))
         drop-ids (->> terminal-for-key (drop keep-tail) (map first))]
     (if (seq drop-ids)
       (-> runtime-db
           (update :rf.runtime/work-ledger
                   (fn [l] (reduce dissoc l drop-ids)))
           ;; keep the inverse index in step with the dropped rows
           (update-in [work-ledger-by-key-key rk-id]
                      (fn [s] (let [s' (reduce disj (or s #{}) drop-ids)]
                                (when (seq s') s'))))
           ;; drop an emptied bucket entirely (recompute shape: no empty sets)
           (as-> rdb (if (nil? (get-in rdb [work-ledger-by-key-key rk-id]))
                       (update rdb work-ledger-by-key-key dissoc rk-id)
                       rdb)))
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
  (swap! handle-table assoc [frame-id (work-id-id work-id)] handle)
  nil)

(defn get-handle
  "Read the host handle for `[frame-id work-id]` from the side table, or
  nil (when absent — already cleared, or a transport that records none).
  Keyed on `[frame-id (work-id-id work-id)]` (rf2-9e0tyq) so a list- and a
  vector-params work-id never collapse in the side table."
  [frame-id work-id]
  (get @handle-table [frame-id (work-id-id work-id)]))

(defn clear-handle!
  "Drop the host handle for `[frame-id work-id]` from the side table
  (without firing its abort — the caller decides whether to abort). Called
  when a work record terminates so a settled attempt's handle does not leak.
  Idempotent. Returns nil."
  [frame-id work-id]
  (swap! handle-table dissoc [frame-id (work-id-id work-id)])
  nil)

;; Forward declaration — `abort-handle!` reads the managed-HTTP transport
;; marker (`managed-abort-fx-transport`, defined below alongside the
;; in-cascade `abort-fx` builder) to decide whether to fire the
;; `:http/abort-in-flight!` hook for a recorded slot (rf2-rak684).
(declare managed-abort-fx-transport)

(defn- abort-handle!
  "Best-effort abort the transport request a recorded side-table `handle`
  describes, firing whatever abort capability the slot carries:

   - a DIRECT host `:abort-fn` (a future transport that hands the runtime a
     live handle fills this slot — e.g. a non-HTTP transport), and/or
   - for MANAGED HTTP, the frame-qualified `:request-id` the slot records
     (`[:rf.req frame-id work-id]`) routed through the `:http/abort-in-flight!`
     late-bind hook the http artefact publishes (rf2-rak684).

  The managed-HTTP arm is THE out-of-cascade teardown seam: the resource
  runtime does not itself hold the AbortController (the managed-HTTP
  transport owns it, keyed by request-id), so the work-handle side-table
  slot records ONLY the correlation `:request-id` and no live `:abort-fn`
  (see `record-work-handle-handler`). The in-cascade lifecycle events
  (`:rf.resource/remove`, clear-scope, refetch supersession, owner-release)
  abort by emitting `:rf.http/managed-abort` via `abort-fx`; the
  OUT-of-cascade lifecycle paths (`clear-resource`, frame destroy) run no
  cascade, so they reach the SAME abort-by-request-id operation
  (`re-frame.http.registry/abort-in-flight!`) through the published hook —
  the same token the lower registered, so it resolves the right frame's
  in-flight request rather than just clearing the side-table slot.

  Opportunistic — a no-op when no transport is holding the request (the
  reply already landed, or the http artefact is not on the classpath, in
  which case the hook resolves to nil). Correctness still rests on the
  work-id + generation stale-suppression gate, NOT on the cancel landing.
  Never throws (a throwing abort-fn / hook is swallowed — a failed cancel
  must not strand the teardown). Returns true iff an abort capability was
  found and fired (a hint for the caller's trace), false otherwise."
  [handle]
  (let [direct? (boolean (when-let [abort-fn (:abort-fn handle)]
                           (try (abort-fn :resource-superseded) true
                                (catch #?(:clj Throwable :cljs :default) _ false))))
        managed? (boolean
                   (when (and (= (:transport handle) managed-abort-fx-transport)
                              (:request-id handle))
                     (try
                       (when-let [abort! (late-bind/get-fn :http/abort-in-flight!)]
                         (boolean (abort! (:request-id handle) :resource-superseded)))
                       (catch #?(:clj Throwable :cljs :default) _ false))))]
    (or direct? managed?)))

(defn opportunistic-abort!
  "BEST-EFFORT cancel the host handle for `[frame-id work-id]`, then drop it
  from the side table. Per Spec 016 §Cancellation is opportunistic; stale
  suppression is mandatory: this MAY abort the in-flight request if the host
  handle exists and can be cancelled; if it cannot, correctness still rests
  on the work-id + generation stale-suppression check (NOT on the cancel
  landing). For managed HTTP the slot records only the frame-qualified
  `:request-id` (no live `:abort-fn`), so the abort rides the
  `:http/abort-in-flight!` late-bind hook by that request-id (rf2-rak684) —
  this is the out-of-cascade counterpart to the in-cascade `:rf.http/managed-abort`
  fx. Returns true iff an abort capability was found and fired (a hint for
  the caller's trace), false otherwise. Never throws (a throwing abort-fn is
  swallowed — a failed cancel must not strand the teardown)."
  [frame-id work-id]
  (let [aborted? (abort-handle! (get-handle frame-id work-id))]
    (clear-handle! frame-id work-id)
    aborted?))

(defn- abort-slot!
  "Best-effort abort + drop a side-table slot addressed by its ALREADY-COMPUTED
  table key `[frame-id work-id-id]` (rf2-9e0tyq). The frame-teardown / reset
  paths iterate `(keys @handle-table)` (which are already byte-keyed pairs) and
  must NOT re-transform the work-id-id through `work-id-id` (the public
  `opportunistic-abort!` takes a work-id VECTOR and transforms it; this private
  variant takes the table key directly). Aborts managed HTTP by the recorded
  frame-qualified `:request-id` through the `:http/abort-in-flight!` hook
  (rf2-rak684), so frame destroy cancels the underlying in-flight request
  before dropping the generation high-water — a surviving host reply can never
  match a future same-id frame. Never throws."
  [slot-key]
  (abort-handle! (get @handle-table slot-key))
  (swap! handle-table dissoc slot-key)
  nil)

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
    (doseq [slot-key slots]
      (abort-slot! slot-key)))
  nil)

(defn reset-cache!
  "Drop EVERY frame's work-ledger host handles (test isolation). Published
  as a reset hook so the shared CLJS `make-reset-runtime-fixture`
  reset-hooks table clears it per test (host-side transient state, NOT
  cleared by the runtime / frames reset). Best-effort aborts each handle on
  the way out. Returns nil."
  []
  (doseq [slot-key (keys @handle-table)]
    (abort-slot! slot-key))
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
