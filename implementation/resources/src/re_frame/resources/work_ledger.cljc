(ns re-frame.resources.work-ledger
  "The frame work ledger: in-flight attempts as work facts, linked to but
  never merged into the cache entries they serve.

  Two halves. Serializable rows live at `[:rf.runtime/work-ledger <id>]` in
  runtime-db and carry no host handles, so they ride epoch snapshots and the
  Xray projection; SSR hydration ships none of them. Host handles live in a
  module-level side table keyed by `[frame-id work-id]`, are never serialized,
  and are released on frame destroy.

  Abort is opportunistic; correctness rests on stale suppression by work-id
  and generation, enforced on the entry by `re-frame.resources.events`.
  The ledger is written only by the Resources artefact's event handlers.
  Spec 016 §Frame work ledger and Spec 016 §Ledger row retention and identity."
  (:require [re-frame.http.registry :as rf.http.registry]
            [re-frame.resources.state :as rf.resources.state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- work-id ----------------------------------------------------------------

(def work-kind-resource
  "The `:work/kind` of a resource or mutation attempt.
  Spec 016 §Frame work ledger."
  :resource)

(defn resource-work-id
  "The work-id `[:rf.work/resource <subject> <generation>]`: the ledger row
  key, the entry's `:current-work` pointer and the stale-suppression
  identity. `subject` is the resource id for a read and
  `[:rf.mutation <instance-id>]` for a mutation.

  It carries no scope or params: the row's `:resource/key` records which
  scoped key the attempt serves. It carries no frame either, and a recorded
  allocation can hand two frames the same generation, so transport
  correlation uses the frame-qualified `managed-request-id`.
  Spec 016 §Ledger row retention and identity."
  [subject generation]
  [:rf.work/resource subject generation])

(defn work-id-resource-id
  "The resource id a read work-id names, or nil for a mutation work-id or a
  non-work-id. Attempt-scoped trace rows stamp it as `:resource/id`, so a row
  names its resource even when its ledger row is gone."
  [work-id]
  (when (and (vector? work-id) (= :rf.work/resource (first work-id)))
    (let [subject (nth work-id 1 nil)]
      (when (keyword? subject) subject))))

(def work-id-id
  "The ledger map key for a work-id: its canonical-bytes string. A mutation
  work-id embeds the caller's instance id, and two ids differing only by list
  vs vector are `=` but distinct identities. The vector stays the `:work/id`
  carried on the row and the wire. Spec 016 §Frame work ledger."
  rf.resources.state/key-id)

(defn managed-request-id
  "The frame-qualified managed-HTTP correlation token
  `[:rf.req <frame-id> <work-id>]`, a deliberate second identity beside the
  work-id. The frame is in it because two frames can hold the same work-id,
  and the request-id is compared across frames in every `:rf.http/*` trace,
  failure and reply. Spec 014 §Frame scope — a `:request-id` is frame-local."
  [frame-id work-id]
  [:rf.req frame-id work-id])

;; ---- status sets -------------------------------------------------------------

(def non-terminal-statuses
  "The live work statuses. Only these rows' summaries ride the epoch-restore
  wire. Spec 016 §Ledger row retention and identity."
  #{:queued :running :abort-requested})

(def terminal-statuses
  "The terminal work statuses, re-bound from
  `re-frame.resources.state/terminal-work-statuses`.
  Spec 016 §Ledger row retention and identity."
  rf.resources.state/terminal-work-statuses)

(defn terminal?
  "True iff `status` is a terminal work status."
  [status]
  (contains? terminal-statuses status))

;; ---- serializable work record -----------------------------------------------

(defn work-record
  "A serializable `:running` row for a fresh attempt; never carries host
  handles. Opts: `:work-id`, `:frame-id` (stamped `:work/frame`),
  `:resource/key`, `:generation`, `:transport`, `:owner` and `:owners`
  (unioned, so a new read starts from the entry's active owners and one
  release never orphans work another owner needs), `:cause`, `:cancellable?`
  (default true), `:started-at`, `:deadline-at`, `:page-index` (an infinite
  feed's page; a positive index marks a load-more) and `:reply-targets`
  (call-site continuations; a read joins in flight, so they accumulate on the
  row). `:page-index` and `:reply-targets` are omitted when absent.
  Spec 016 §Frame work ledger."
  [{:keys [work-id frame-id generation transport
           owner owners cause cancellable? started-at deadline-at page-index reply-targets]
    resource-key :resource/key}]
  (cond-> {:work/id      work-id
           :work/kind    work-kind-resource
           :work/frame   frame-id
           :resource/key resource-key
           :generation   generation
           :transport    transport
           :status       :running
           :owners       (cond-> (set owners) owner (conj owner))
           :causes       (if cause [cause] [])
           :cancellable? (if (some? cancellable?) cancellable? true)
           :started-at   started-at
           :deadline-at  deadline-at}
    (some? page-index) (assoc :page-index page-index)
    (seq reply-targets) (assoc :reply-targets (vec reply-targets))))

(defn join-owner+cause
  "Joins a supplementary ensure onto a live row: adds `owner` and appends
  `cause`, with no status change. Nil-safe. Spec 016 §Race and in-flight semantics."
  [record owner cause]
  (cond-> record
    owner (update :owners (fnil conj #{}) owner)
    cause (update :causes (fnil conj []) cause)))

(defn add-reply-target
  "Appends a data-only `:reply-to` `target` to `:reply-targets`, deduped and
  in order, so one accepted reply fans out to each target exactly once. A nil
  target is a no-op.
  Spec 016 §Read completion continuations — call-site `:reply-to`."
  [record target]
  (if (nil? target)
    record
    (update record :reply-targets
            (fn [ts] (let [ts (or ts [])]
                       (if (some #(= % target) ts) ts (conj ts target)))))))

(defn release-owner-from-record
  "Drops `owner` from `:owners` without changing status; aborting is a
  separate decision, taken only when no owner remains.
  Spec 016 §Race and in-flight semantics."
  [record owner]
  (update record :owners (fnil disj #{}) owner))

(defn mark-terminal
  "Sets a terminal `status` and a small serializable `outcome` summary.
  Prefer `settle-terminal`, which also prunes."
  [record status outcome]
  (assoc record :status status :outcome outcome))

(defn mark-abort-requested
  "Moves a row to the non-terminal `:abort-requested`: a cancel was issued
  but the attempt has not settled. Spec 016 §Frame work ledger."
  [record]
  (assoc record :status :abort-requested))

(defn serializable-record?
  "True iff `record` is host-handle-free serializable EDN, the invariant
  every row must hold. Spec 016 §Frame work ledger."
  [record]
  (rf.resources.state/serializable-edn? record))

;; ---- runtime-db record bookkeeping ------------------------------------------

(defn record-path
  "Runtime-db path to a row, keyed on the byte `work-id-id`."
  [work-id]
  [:rf.runtime/work-ledger (work-id-id work-id)])

;; ---- resource-key → work-id inverse index -----------------------------------
;;
;; `{<rk-id> #{<work-id-id> …}}` lets the per-settle prune visit only the
;; settling key's rows instead of the whole ledger. It is a derived projection,
;; never trusted from a snapshot: a wholesale ledger install (epoch restore,
;; replace-frame-state!, router rollback, fixtures) arrives without it, and the
;; per-key droppers rebuild it once on finding the ledger populated but the
;; index absent. So the incremental maintainers act only when the index already
;; exists: a `put-record` that half-built it would mask that rebuild.

(def work-ledger-by-key-key
  "Reserved runtime-db key of the resource-key → work-id-id inverse index.
  Spec 016 §Ledger row retention and identity."
  :rf.runtime/work-ledger-by-key)

(defn recompute-ledger-index
  "Rebuilds the inverse index from the ledger in `runtime-db`. Returns the
  updated runtime-db."
  [runtime-db]
  (let [ledger (:rf.runtime/work-ledger runtime-db)
        idx    (reduce-kv
                 (fn [acc wid-id record]
                   (let [rk-id (rf.resources.state/key-id (:resource/key record))]
                     (update acc rk-id (fnil conj #{}) wid-id)))
                 {}
                 ledger)]
    (assoc runtime-db work-ledger-by-key-key idx)))

(defn- index-present? [runtime-db] (contains? runtime-db work-ledger-by-key-key))

(defn- index-add
  "Adds `work-id-id*` to its bucket, only when the index exists."
  [runtime-db rk-id work-id-id*]
  (if (index-present? runtime-db)
    (update-in runtime-db [work-ledger-by-key-key rk-id] (fnil conj #{}) work-id-id*)
    runtime-db))

(defn- ensure-ledger-index
  "Rebuilds the index once when the ledger is populated but the index is
  absent (the post-install shape). An empty ledger leaves it absent, keeping
  the rebuild signal for whatever installs rows next."
  [runtime-db]
  (if (and (seq (:rf.runtime/work-ledger runtime-db))
           (not (index-present? runtime-db)))
    (recompute-ledger-index runtime-db)
    runtime-db))

(defn put-record
  "Writes `record` under `work-id` and registers it in the live index.
  Returns the updated runtime-db."
  [runtime-db work-id record]
  (-> runtime-db
      (assoc-in (record-path work-id) record)
      (index-add (rf.resources.state/key-id (:resource/key record)) (work-id-id work-id))))

(defn get-record
  "The row under `work-id`, or nil."
  [runtime-db work-id]
  (get-in runtime-db (record-path work-id)))

(defn live-work?
  "True iff `work-id`'s row exists and is neither terminal nor
  `:abort-requested`. An entry's `:current-work` pointer is not proof of
  work: it survives a route supersession and an aborted settlement, so the
  linked row's status is the liveness fact. The one definition used by the
  ensure join, load-more dedupe and route-plan adoption.
  Spec 016 §Race and in-flight semantics."
  [runtime-db work-id]
  (let [status (:status (get-record runtime-db work-id))]
    (boolean (and (some? status)
                  (not (terminal? status))
                  (not= :abort-requested status)))))

(defn update-record
  "Applies `f` and `args` to the row under `work-id`; a no-op when the row is
  gone. Returns the updated runtime-db."
  [runtime-db work-id f & args]
  (if (get-record runtime-db work-id)
    (apply update-in runtime-db (record-path work-id) f args)
    runtime-db))

(defn update-record-by-id
  "`update-record` keyed by an already-computed byte `work-id-id`, for a
  caller iterating the ledger map; re-transforming it would miss."
  [runtime-db work-id-id f & args]
  (if (get-in runtime-db [:rf.runtime/work-ledger work-id-id])
    (apply update-in runtime-db [:rf.runtime/work-ledger work-id-id] f args)
    runtime-db))

(def default-terminal-tail
  "Terminal rows retained per resource key for Xray's recent-races view.
  Small because the ledger rides every epoch snapshot.
  Spec 016 §Ledger row retention and identity."
  3)

(defn prune-terminal-for-key
  "Prunes `resource-key`'s terminal rows, keeping the `keep-tail` (default
  `default-terminal-tail`) most recently started; live rows are never
  pruned. Returns the updated runtime-db. Callers use `settle-terminal`.
  Spec 016 §Ledger row retention and identity."
  ([runtime-db resource-key] (prune-terminal-for-key runtime-db resource-key default-terminal-tail))
  ([runtime-db resource-key keep-tail]
   (let [runtime-db (ensure-ledger-index runtime-db)
         ;; Byte identity, so a list- and a vector-params key never prune
         ;; each other's rows.
         rk-id  (rf.resources.state/key-id resource-key)
         ledger (:rf.runtime/work-ledger runtime-db)
         this-key-ids (get-in runtime-db [work-ledger-by-key-key rk-id])
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
           (update-in [work-ledger-by-key-key rk-id]
                      (fn [s] (let [s' (reduce disj (or s #{}) drop-ids)]
                                (when (seq s') s'))))
           ;; No empty buckets: match the recompute shape.
           (as-> rdb (if (nil? (get-in rdb [work-ledger-by-key-key rk-id]))
                       (update rdb work-ledger-by-key-key dissoc rk-id)
                       rdb)))
       runtime-db))))

(defn settle-terminal
  "Marks the row under `work-id` terminal and prunes its key's tail in one
  step; the seam the bounded-ledger promise rests on. Pruning only on success
  would bound nothing for a key that never succeeds, such as a poll against a
  failing endpoint. The prune key comes from the settled row, which survives
  as the newest terminal row. A no-op when the row is gone. Returns the
  updated runtime-db. Spec 016 §Ledger row retention and identity."
  [runtime-db work-id status outcome]
  (let [runtime-db (update-record runtime-db work-id mark-terminal status outcome)]
    (if-let [resource-key (:resource/key (get-record runtime-db work-id))]
      (prune-terminal-for-key runtime-db resource-key)
      runtime-db)))

(defn drop-rows-for-key
  "Drops every row linked to `resource-key`, live or terminal, and its index
  bucket. Returns the updated runtime-db. Call it wherever the linked entry
  or mutation instance leaves the cache: a removed key has no recent-races
  view to serve, so a retained tail would be unbounded growth. Live rows go
  too, because the caller has already settled and aborted them and a late
  reply is stale-suppressed. Spec 016 §Ledger row retention and identity."
  [runtime-db resource-key]
  (let [runtime-db (ensure-ledger-index runtime-db)
        rk-id      (rf.resources.state/key-id resource-key)
        row-ids    (get-in runtime-db [work-ledger-by-key-key rk-id])]
    (if (seq row-ids)
      (-> runtime-db
          (update :rf.runtime/work-ledger (fn [l] (reduce dissoc l row-ids)))
          (update work-ledger-by-key-key dissoc rk-id))
      runtime-db)))

;; ---- host-side handle side table --------------------------------------------

(defonce
  ^{:doc "`[frame-id work-id-id]` → a host-handle map such as
   `{:abort-fn <thunk> :transport :rf.http/managed}`. Transient host state:
   an epoch restore cannot rewind it and it never rides the wire.
   Runtime-Subsystems §5. Teardown."}
  handle-table
  (atom {}))

(defn put-handle!
  "Records `handle` for `[frame-id work-id]`. Returns nil."
  [frame-id work-id handle]
  (swap! handle-table assoc [frame-id (work-id-id work-id)] handle)
  nil)

(defn get-handle
  "The host handle for `[frame-id work-id]`, or nil."
  [frame-id work-id]
  (get @handle-table [frame-id (work-id-id work-id)]))

(defn clear-handle!
  "Drops the handle for `[frame-id work-id]` without firing its abort.
  Idempotent; returns nil."
  [frame-id work-id]
  (swap! handle-table dissoc [frame-id (work-id-id work-id)])
  nil)

(declare managed-abort-fx-transport)

(defn- abort-handle!
  "Best-effort aborts the request `handle` describes in `frame-id`: a direct
  `:abort-fn`, and for managed HTTP the recorded frame-qualified
  `:request-id` through `re-frame.http.registry/abort-in-flight-in-frame!`.
  The managed transport owns the AbortController, so this is the
  out-of-cascade teardown path (`clear-resource`, frame destroy); in-cascade
  paths emit `abort-fx`. Never throws. Returns true iff an abort fired.
  Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  [frame-id handle]
  (let [direct? (boolean (when-let [abort-fn (:abort-fn handle)]
                           (try (abort-fn :resource-superseded) true
                                (catch #?(:clj Throwable :cljs :default) _ false))))
        managed? (boolean
                   (when (and (= (:transport handle) managed-abort-fx-transport)
                              (:request-id handle))
                     (try
                       (rf.http.registry/abort-in-flight-in-frame!
                         frame-id (:request-id handle) :resource-superseded)
                       (catch #?(:clj Throwable :cljs :default) _ false))))]
    (or direct? managed?)))

;; ---- detach-then-abort ------------------------------------------------------
;;
;; An abort fires a callback into app or host code. Outside a drain it can
;; churn frame incarnation A to a same-id successor B, which seats its own
;; handle under the same `[frame-id work-id]` key, and a re-entrant callback
;; can re-read a not-yet-dropped slot. A read-abort-dissoc cleanup would then
;; delete B's live handle or abort twice. So the order is fixed: detach the
;; slots atomically first, then abort the detached values. `timers` uses the
;; same discipline.

(defn- detach-slots!
  "Atomically removes every slot whose key satisfies `key-pred` and returns
  the detached `[[frame-id work-id-id] handle]` entries."
  [key-pred]
  (let [[old _new] (swap-vals! handle-table
                               (fn [m] (into {} (remove (comp key-pred key)) m)))]
    (filterv (comp key-pred key) old)))

(defn- abort-slot!
  "`abort-handle!` for one detached slot."
  [[[frame-id _] handle]]
  (abort-handle! frame-id handle))

(defn opportunistic-abort!
  "Detaches then best-effort aborts the handle for `[frame-id work-id]`; the
  out-of-cascade counterpart of `:rf.http/managed-abort`. Never throws.
  Returns true iff an abort fired.
  Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  [frame-id work-id]
  (let [slot-key [frame-id (work-id-id work-id)]]
    (boolean (some abort-slot! (detach-slots! #{slot-key})))))

(defn- abort-detached!
  "Best-effort aborts already-detached `slots` in order. A reply outliving
  the abort never matches a future same-id frame, because the generation
  high-water is process-wide. Returns nil."
  [slots]
  (run! abort-slot! slots)
  nil)

;; ---- opportunistic abort via the transport fx --------------------------------

(def managed-abort-fx
  "The managed-HTTP abort fx-id. Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  :rf.http/managed-abort)

(def managed-abort-fx-transport
  "The transport whose abort rides `managed-abort-fx`. Spec 016 §Transport."
  :rf.http/managed)

(defn abort-fx
  "The best-effort abort fx `[:rf.http/managed-abort <managed-request-id>]`
  for a superseded or released attempt, or nil for a transport with no abort
  capability. `frame-id` is the row's `:work/frame`; the token must be the
  frame-qualified one the lower registered, or the abort misses.
  Spec 014 §`:request-id` (internal)."
  [transport frame-id work-id]
  (when (= transport managed-abort-fx-transport)
    [managed-abort-fx (managed-request-id frame-id work-id)]))

;; ---- side-table write fx ------------------------------------------------------
;;
;; The side table is host state, so its writes ride fx rather than a
;; runtime-db effect.

(def record-work-handle-meta
  "Registration metadata for the `:rf.resource/record-work-handle` fx."
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
  "`:rf.resource/record-work-handle` fx handler. Args:
  `{:frame-id … :work-id … :transport … :request-id … :abort-fn …}`."
  [_ctx {:keys [frame-id work-id transport request-id abort-fn]}]
  (put-handle! frame-id work-id
               (cond-> {:transport transport :request-id request-id}
                 abort-fn (assoc :abort-fn abort-fn)))
  nil)

(def clear-work-handle-meta
  "Registration metadata for the `:rf.resource/clear-work-handle` fx."
  {:doc "Drop a work-ledger host handle from the host-side side table, keyed
by `[frame-id work-id]`. Args: `{:frame-id … :work-id …}`. Emitted when an
attempt is superseded / settled so a stale handle does not leak. Per Spec 016
§Frame work ledger."})

(defn clear-work-handle-handler
  "`:rf.resource/clear-work-handle` fx handler. Args:
  `{:frame-id … :work-id …}`."
  [_ctx {:keys [frame-id work-id]}]
  (clear-handle! frame-id work-id)
  nil)

;; ---- frame teardown -----------------------------------------------------------

(defn release-frame!
  "Detaches and best-effort aborts every handle of a destroyed `frame-id`.
  Detaching first means an abort callback that seats a same-id successor
  keeps its handle, and a re-entrant call finds nothing to abort twice. The
  durable rows go with the frame value. Idempotent; returns nil.
  Runtime-Subsystems §5. Teardown."
  [frame-id]
  (abort-detached! (detach-slots! (fn [[fid _]] (= fid frame-id)))))

(defn reset-cache!
  "Detaches and best-effort aborts every frame's handles, for test isolation;
  the runtime / frames reset does not clear this host state. Detaching first
  keeps a handle an abort callback records. Returns nil."
  []
  (abort-detached! (first (reset-vals! handle-table {}))))

(defn on-frame-destroyed!
  "The work-ledger half of the `:resources/on-frame-destroyed!` teardown
  hook, which the façade composes with the timer and revalidation-listener
  releases. Returns nil. Runtime-Subsystems §5. Teardown."
  [frame-id]
  (release-frame! frame-id)
  nil)
