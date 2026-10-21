(ns re-frame.resources.work-ledger
  "Resource and mutation attempts: the work id, the attempt record its owner
  keeps, and the host-handle side table.

  An attempt lives on the entry or mutation instance it serves: the live one
  under `:work`, the summaries of the last few terminal ones under
  `:recent-work`. Both are serializable and carry no host handles, so they
  ride epoch snapshots and the Xray projection, and they leave with their
  owner; SSR hydration ships neither. Host handles live in a module-level side
  table keyed by `[frame-id work-id]`, are never serialized, and are released
  on frame destroy.

  Abort is opportunistic; correctness rests on stale suppression by work-id
  and generation against the owner's `:current-work`, enforced by
  `re-frame.resources.events` and `re-frame.resources.mutation-events`.
  Spec 016 §In-flight attempts."
  (:require [re-frame.http.registry :as rf.http.registry]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.state :as rf.resources.state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- work-id ----------------------------------------------------------------

(def work-kind-resource
  "The `:work/kind` of a resource attempt; a mutation's is `:mutation`.
  Spec 016 §In-flight attempts."
  :resource)

(defn resource-work-id
  "The work-id `[:rf.work/resource <subject> <generation>]`: the attempt's
  `:work/id`, its owner's `:current-work` pointer and the stale-suppression
  identity. `subject` is the resource id for a read and
  `[:rf.mutation <instance-id>]` for a mutation.

  It carries no scope or params: the attempt lives on the entry for the
  scoped key it serves. It carries no frame either, and a recorded allocation
  can hand two frames the same generation, so transport correlation uses the
  frame-qualified `managed-request-id`. Spec 016 §Attempt identity."
  [subject generation]
  [:rf.work/resource subject generation])

(defn work-id-resource-id
  "The resource id a read work-id names, or nil for a mutation work-id or a
  non-work-id. Attempt-scoped trace rows stamp it as `:resource/id`, so a row
  names its resource even when its attempt has left with its entry."
  [work-id]
  (when (and (vector? work-id) (= :rf.work/resource (first work-id)))
    (let [subject (nth work-id 1 nil)]
      (when (keyword? subject) subject))))

(def work-id-id
  "The side-table key for a work-id: its canonical-bytes string. A mutation
  work-id embeds the caller's instance id, and two ids differing only by list
  vs vector are `=` but distinct identities. Spec 016 §Attempt identity."
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
  "The statuses of a live attempt, the ones a restore dangles.
  Spec 016 §Restore and replay."
  #{:queued :running :abort-requested})

(def terminal-statuses
  "The terminal attempt statuses, re-bound from
  `re-frame.resources.state/terminal-work-statuses`.
  Spec 016 §In-flight attempts."
  rf.resources.state/terminal-work-statuses)

(defn terminal?
  "True iff `status` is a terminal attempt status."
  [status]
  (contains? terminal-statuses status))

;; ---- serializable attempt record --------------------------------------------

(defn work-record
  "A serializable `:running` record for a fresh attempt, which its owner keeps
  under `:work`; never carries host handles. Opts: `:work-id`, `:generation`,
  `:transport`, `:owner` and `:owners` (unioned, so a new read starts from the
  entry's active owners and one release never orphans work another owner
  needs), `:cause`, `:cancellable?` (default true), `:started-at`,
  `:deadline-at`, `:page-index` (an infinite feed's page; a positive index
  marks a load-more) and `:reply-targets` (call-site continuations; a read
  joins in flight, so they accumulate on the attempt). `:page-index` and
  `:reply-targets` are omitted when absent. Spec 016 §In-flight attempts."
  [{:keys [work-id generation transport
           owner owners cause cancellable? started-at deadline-at page-index reply-targets]}]
  (cond-> {:work/id      work-id
           :work/kind    work-kind-resource
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
  "Joins a supplementary ensure onto a live attempt: adds `owner` and appends
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
  "Sets a terminal `status` and a small serializable `outcome` summary on a
  record. On an owner, use `settle-attempt`, which also moves the attempt to
  its terminal tail."
  [record status outcome]
  (assoc record :status status :outcome outcome))

(defn mark-abort-requested
  "Moves an attempt to the non-terminal `:abort-requested`: a cancel was
  issued but the attempt has not settled. Spec 016 §In-flight attempts."
  [record]
  (assoc record :status :abort-requested))

(defn serializable-record?
  "True iff `record` is host-handle-free serializable EDN, the invariant
  every attempt record must hold. Spec 016 §In-flight attempts."
  [record]
  (rf.resources.state/serializable-edn? record))

;; ---- the attempt on its owner -------------------------------------------------
;;
;; An entry or mutation instance keeps its live attempt under `:work` and the
;; summaries of its last terminal attempts, newest first, under
;; `:recent-work`. The owner's `:current-work` and `:generation` stay the stale
;; gate; `:work` holds the attempt's facts.

(def recent-work-limit
  "Terminal summaries an owner keeps for Xray's dimmed tail. Small because
  they ride every epoch snapshot. Spec 016 §In-flight attempts."
  3)

(defn attempt-summary
  "The terminal summary an owner keeps of a settled attempt: its work id,
  kind, generation, status, outcome, start time and page index."
  [record]
  (select-keys record [:work/id :work/kind :generation :status :outcome :started-at :page-index]))

(defn live-attempt
  "`owner`'s live attempt when its id is `work-id`, else nil."
  [owner work-id]
  (let [record (:work owner)]
    (when (and (some? work-id) (= work-id (:work/id record)))
      record)))

(defn find-attempt
  "`owner`'s attempt `work-id`: the live record, else its terminal summary,
  else nil."
  [owner work-id]
  (or (live-attempt owner work-id)
      (some #(when (= work-id (:work/id %)) %) (:recent-work owner))))

(defn live-work?
  "True iff `owner`'s `:current-work` names its live attempt and that attempt
  is neither terminal nor `:abort-requested`. The pointer alone is not proof
  of work: it outlives an abort request, so the attempt's status is the
  liveness fact. The one definition the ensure join, load-more dedupe, the
  revalidation scans and route readiness use.
  Spec 016 §Race and in-flight semantics."
  [owner]
  (let [status (:status (live-attempt owner (:current-work owner)))]
    (boolean (and (some? status)
                  (not (terminal? status))
                  (not= :abort-requested status)))))

(defn update-attempt
  "`owner` with `f` and `args` applied to its live attempt `work-id`; a no-op
  when that is not the live attempt."
  [owner work-id f & args]
  (if (live-attempt owner work-id)
    (apply update owner :work f args)
    owner))

(defn settle-attempt
  "`owner` with attempt `work-id` settled terminal `status` with the small
  serializable `outcome`. A live attempt moves to the head of `:recent-work`,
  which keeps the newest `recent-work-limit`; every settle trims, not only a
  success, so an owner that never succeeds, such as a poll against a failing
  endpoint, keeps a bounded tail. An attempt already in the tail is re-marked
  in place, so the late reply of a superseded attempt still records what it
  reported. A no-op for any other id. Spec 016 §In-flight attempts."
  [owner work-id status outcome]
  (if-let [record (live-attempt owner work-id)]
    (-> owner
        (dissoc :work)
        (assoc :recent-work
               (into [(attempt-summary (mark-terminal record status outcome))]
                     (take (dec recent-work-limit))
                     (:recent-work owner))))
    (if (some #(= work-id (:work/id %)) (:recent-work owner))
      (update owner :recent-work
              (fn [tail] (mapv #(cond-> % (= work-id (:work/id %)) (mark-terminal status outcome))
                               tail)))
      owner)))

(defn start-attempt
  "`owner` with `record` as its live attempt. A live attempt it displaces can
  never settle the owner again, since `:current-work` has moved on, so it
  settles `:suppressed`, superseded by `record`.
  Spec 016 §Race and in-flight semantics."
  [owner record]
  (let [prior (:work owner)]
    (-> (if (some? prior)
          (settle-attempt owner (:work/id prior) :suppressed
                          {:reason :superseded :by (:work/id record)})
          owner)
        (assoc :work record))))

;; ---- finding an attempt's owner -----------------------------------------------

(defn attempt-owner-path
  "The runtime-db path of the entry or mutation instance holding attempt
  `work-id`, live or among its terminal summaries, or nil. A read work id
  names its resource, so only that resource's entries are searched, and a
  live attempt wins over a summary; a mutation work id names its instance.
  Spec 016 §Attempt identity."
  [runtime-db work-id]
  (let [subject (when (vector? work-id) (nth work-id 1 nil))]
    (cond
      (and (vector? subject) (= :rf.mutation (first subject)))
      (let [path (rf.resources.mutation-runtime/instance-path (second subject))]
        (when (find-attempt (get-in runtime-db path) work-id)
          path))

      (keyword? subject)
      (let [entries (get-in runtime-db (rf.resources.state/entries-path))
            holder  (fn [held?]
                      (some (fn [[k-id entry]]
                              (when (and (= subject (:resource/id entry)) (held? entry))
                                (rf.resources.state/entry-path-by-id k-id)))
                            entries))]
        (or (holder #(live-attempt % work-id))
            (holder #(find-attempt % work-id)))))))

(defn attempt-record
  "Attempt `work-id`'s record in `runtime-db`: the live record, or its
  terminal summary, or nil when it has left with its owner."
  [runtime-db work-id]
  (when-let [path (attempt-owner-path runtime-db work-id)]
    (find-attempt (get-in runtime-db path) work-id)))

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

(defn- abort-direct!
  "Fires `handle`'s direct `:abort-fn`, if it carries one. Never throws.
  Returns true iff the abort-fn ran."
  [handle]
  (boolean (when-let [abort-fn (:abort-fn handle)]
             (try (abort-fn :resource-superseded) true
                  (catch #?(:clj Throwable :cljs :default) _ false)))))

(defn- abort-handle!
  "Best-effort aborts the request `handle` describes in `frame-id`: a direct
  `:abort-fn`, and for managed HTTP the recorded frame-qualified
  `:request-id` through `re-frame.http.registry/abort-in-flight-in-frame!`.
  The managed transport owns the AbortController, so this is the
  out-of-cascade teardown path (`clear-resource`, the test reset); in-cascade
  paths emit `abort-fx`. Never throws. Returns true iff an abort fired.
  Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  [frame-id handle]
  (let [direct?  (abort-direct! handle)
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
  capability. `frame-id` is the issuing frame's id; the token must be the
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
  {:doc "Record an attempt's host handle in the host-side side table, keyed
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
§In-flight attempts."})

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
  {:doc "Drop an attempt's host handle from the host-side side table, keyed
by `[frame-id work-id]`. Args: `{:frame-id … :work-id …}`. Emitted when an
attempt is superseded / settled so a stale handle does not leak. Per Spec 016
§In-flight attempts."})

(defn clear-work-handle-handler
  "`:rf.resource/clear-work-handle` fx handler. Args:
  `{:frame-id … :work-id …}`."
  [_ctx {:keys [frame-id work-id]}]
  (clear-handle! frame-id work-id)
  nil)

;; ---- frame teardown -----------------------------------------------------------

(defn release-frame!
  "Detaches every handle of a destroyed `frame-id` and best-effort fires each
  direct `:abort-fn`. A managed-HTTP slot is only detached: http's own
  frame-destroy sweep aborts every request the frame issued with its reply
  suppressed, so the request ends the same way whichever artefact tears down
  first, and nothing replies into the dead frame. Detaching first means an
  abort callback that seats a same-id successor keeps its handle, and a
  re-entrant call finds nothing to abort twice. The attempts go with their
  owners in the frame value. Idempotent; returns nil. Runtime-Subsystems §5.
  Teardown; Spec 014 §Abort on frame destroy."
  [frame-id]
  (run! (fn [[_ handle]] (abort-direct! handle))
        (detach-slots! (fn [[fid _]] (= fid frame-id))))
  nil)

(defn reset-cache!
  "Detaches and best-effort aborts every frame's handles, for test isolation;
  the runtime / frames reset does not clear this host state. Detaching first
  keeps a handle an abort callback records. Returns nil."
  []
  (abort-detached! (first (reset-vals! handle-table {}))))

(defn on-frame-destroyed!
  "The attempt half of the resources contribution's `:post-dead`
  teardown, which the façade composes with the timer and revalidation-listener
  releases. Returns nil. Runtime-Subsystems §5. Teardown."
  [frame-id]
  (release-frame! frame-id)
  nil)
