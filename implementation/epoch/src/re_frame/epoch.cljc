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
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- configuration --------------------------------------------------------

(def ^:private default-depth 50)

(defonce ^:private config
  ;; Currently a single key (:depth) but kept as a map so future
  ;; (rf/configure :epoch-history {...}) extensions don't break the shape.
  (atom {:depth default-depth}))

(defn configure!
  "Update the epoch-history configuration. Currently supports {:depth N}.
  N must be a non-negative integer; 0 disables recording (assembled
  records can still fire on listeners but nothing lands in the ring
  buffer)."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:depth])))
  nil)

(defn current-config
  "Return the current epoch-history configuration map. Public for tests
  and tools that want to display the current depth."
  []
  @config)

(defn- depth []
  (:depth @config default-depth))

;; ---- the per-frame ring buffer --------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history": last N epochs per frame.
;; Stored as a map of frame-id → vector (oldest-first). New records append
;; to the back; the front evicts when the buffer exceeds the configured
;; depth.

(defonce ^:private histories
  (atom {}))

(defn- append-record [history record d]
  (let [history+ (conj (or history []) record)
        n        (count history+)]
    (if (and (pos? d) (> n d))
      (subvec history+ (- n d))
      history+)))

(defn- record!
  "Append a record into the frame's history. The depth cap is read from
  the config atom on each append so runtime (rf/configure ...) takes
  effect immediately."
  [record]
  (let [d (depth)]
    (when (pos? d)
      (let [frame-id (:frame record)]
        (swap! histories update frame-id append-record record d)))))

(defn epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs (or when
  depth is 0, which disables recording)."
  [frame-id]
  (or (get @histories frame-id) []))

(defn clear-history!
  "Drop every recorded epoch for every frame. Test fixtures use this."
  []
  (reset! histories {})
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
  (swap! histories dissoc frame-id)
  nil)

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))

;; Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656):
;; track which frames each cb has been delivered records for. When a
;; frame is destroyed, every cb whose observed-frames set contains
;; that frame receives a one-shot :rf.epoch.cb/silenced-on-frame-destroy
;; trace. The frame is then dropped from the cb's entry so a
;; re-registration of a same-keyed frame (e.g. `reset-frame :app/main`)
;; can re-arm the silencing trace for a future destroy.
(defonce ^:private observed-frames-by-cb
  ;; cb-id → #{frame-id ...}
  (atom {}))

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
  (swap! listeners assoc id f)
  ;; A re-registration under the same id resets the observed-frames set
  ;; so the new callback's silencing trace fires fresh against frames
  ;; the new callback observes.
  (swap! observed-frames-by-cb dissoc id)
  id)

(defn remove-epoch-cb!
  "Remove the listener registered under id."
  [id]
  (swap! listeners dissoc id)
  (swap! observed-frames-by-cb dissoc id)
  nil)

(defn clear-epoch-cbs!
  []
  (reset! listeners {})
  (reset! observed-frames-by-cb {})
  nil)

(defn- record-observation! [cb-id frame-id]
  ;; Guard the swap on the already-observed case. `notify-listeners!`
  ;; calls this once per listener per drain-settle, and for the common
  ;; case (a long-lived listener observing the same frame on every
  ;; cascade) the cb's observed-frames set already contains frame-id —
  ;; an unconditional `swap!` fires every atom watcher for ZERO
  ;; semantic change. Read once, fast-path return when the membership
  ;; already holds; otherwise CAS through the swap.
  (when frame-id
    (let [current @observed-frames-by-cb]
      (when-not (contains? (get current cb-id) frame-id)
        (swap! observed-frames-by-cb
               (fn [m]
                 (if (contains? (get m cb-id) frame-id)
                   m
                   (update m cb-id (fnil conj #{}) frame-id))))))))

(defn- drop-frame-from-cb-observations
  "Drop `frame-id` from every cb's observed-frames set in `m`. When a
  cb's set goes empty as a result, drop the cb entry entirely so the
  map doesn't accrete keys to empty sets."
  [m frame-id]
  (reduce-kv (fn [acc cb-id frames]
               (let [frames' (disj frames frame-id)]
                 (if (empty? frames')
                   (dissoc acc cb-id)
                   (assoc acc cb-id frames'))))
             {}
             m))

(defn- notify-listeners! [record]
  (let [frame-id (:frame record)]
    (doseq [[id f] @listeners]
      (record-observation! id frame-id)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) _
          ;; Per Spec 009 §Listener invocation rules: listener failures
          ;; are isolated. Continue notifying.
          nil)))))

(defn on-frame-destroyed!
  "Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656):

    1. Emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb whose
       observed-frames set contains `frame-id`, then drop `frame-id`
       from each cb's entry so a re-registration of a same-keyed frame
       re-arms the silencing trace for a future destroy.
    2. Drop the destroyed frame's per-frame ring buffer so subsequent
       `(rf/epoch-history frame-id)` calls return the empty vector
       (the read-empty shape the contract commits to).

  Called from `re-frame.frame/destroy-frame!` via the
  `:epoch/on-frame-destroyed` late-bind hook. Idempotent across
  repeated destroys of the same frame — once a cb's entry no longer
  contains the frame-id, no further trace fires for that pair, and
  the (already-cleared) ring-buffer entry stays absent."
  [frame-id]
  (when interop/debug-enabled?
    (let [silenced-cbs (->> @observed-frames-by-cb
                            (keep (fn [[cb-id frames]]
                                    (when (contains? frames frame-id) cb-id)))
                            vec)]
      (doseq [cb-id silenced-cbs]
        (trace/emit! :rf.epoch.cb :rf.epoch.cb/silenced-on-frame-destroy
                     {:frame  frame-id
                      :cb-id  cb-id})))
    (swap! observed-frames-by-cb drop-frame-from-cb-observations frame-id)
    ;; Drop the per-frame ring buffer; epoch-history returns [] from
    ;; here on. (`reset-frame :app/main` calls destroy-frame! followed
    ;; by reg-frame, so the ring buffer for the new same-keyed frame
    ;; starts empty per Spec 002 §reset-frame.)
    (swap! histories dissoc frame-id)))

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

(defonce ^:private capture-buffers
  ;; frame-id → vector of trace events (in arrival order)
  (atom {}))

(defn- buffer-event! [frame-id event]
  (swap! capture-buffers update frame-id (fnil conj []) event))

(defn- harvest-buffer! [frame-id]
  (let [b (get @capture-buffers frame-id [])]
    (swap! capture-buffers dissoc frame-id)
    b))

(defn- in-flight-buffer
  "Return the in-flight capture buffer for frame-id, or empty vector."
  [frame-id]
  (get @capture-buffers frame-id []))

;; Operations this namespace itself emits with a `:frame` tag, all of
;; which fire OUTSIDE a cascade (the drain has either not started, or
;; has just settled and the buffer has been harvested). If `capture-
;; event!` didn't skip them they would accrete into `capture-buffers`
;; and leak into the NEXT cascade's harvested record for the same
;; frame — a silent correctness bug surfacing as phantom `:trace-events`
;; and a wrong `:trigger-event` via `find-trigger-event`'s fallback arm.
;;
;; Enumeration (not a `:rf.epoch/*` namespace-prefix filter) is the
;; deliberate choice: a future in-cascade `:rf.epoch/*` op (e.g. an
;; in-drain cascade-rollback trace) must continue to surface in epoch
;; records. The companion test `skip-ops-catalogue-pins-every-rf-epoch-op`
;; pins this catalogue against every `:rf.epoch/*` op the namespace
;; emits, so an addition that forgets to update one or the other will
;; fail loudly rather than drift silently.
;;
;; `:rf.epoch.cb/silenced-on-frame-destroy` is a different op-type
;; (`:rf.epoch.cb`) and emits AFTER the frame's ring buffer has been
;; dropped, so it can never race a future cascade for that frame.
(def ^:private skip-ops
  #{;; Drain-settle emit (after harvest-buffer! has emptied the buffer).
    :rf.epoch/snapshotted
    ;; restore-epoch success + the five documented failure modes.
    :rf.epoch/restored
    :rf.epoch/restore-unknown-epoch
    :rf.epoch/restore-schema-mismatch
    :rf.epoch/restore-missing-handler
    :rf.epoch/restore-version-mismatch
    :rf.epoch/restore-during-drain
    ;; reset-frame-db! success + its two failure modes (Tool-Pair §Pair-
    ;; tool writes, rf2-zq55). All three fire after the synthetic record
    ;; has been built and the cascade-buffer (if any) has been harvested.
    :rf.epoch/db-replaced
    :rf.epoch/reset-frame-db-during-drain
    :rf.epoch/reset-frame-db-schema-mismatch})

(defn- capture-event!
  "Internal trace-capture entry point published through `re-frame.late-bind`
  under `:epoch/capture-event`. `re-frame.trace/emit!` and
  `re-frame.trace/emit-error!` invoke this for every event so the
  cascade buffer is populated regardless of which user listeners are
  registered.

  Going through late-bind (rather than registering as a listener via
  `register-trace-cb!`) ensures the user-facing `clear-trace-cbs!`
  call does NOT wipe the internal capture path — pair tools that reset
  the trace stream between sessions can do so without losing epoch
  recording.

  Events whose tags don't carry `:frame` are skipped — they can't be
  tied to a specific cascade. The `:rf.epoch/*` trace events this
  namespace emits OUTSIDE a cascade (catalogued in `skip-ops`) are
  also skipped, so a snapshotted/restored/db-replaced emit cannot leak
  into the next cascade's harvested record."
  [event]
  (when interop/debug-enabled?
    (let [op       (:operation event)
          tags     (:tags event)
          frame-id (or (:frame tags)
                       (:frame event))]
      (when (and frame-id (not (contains? skip-ops op)))
        (buffer-event! frame-id event)))))

;; ---- record projection ----------------------------------------------------

(defn- project-sub-runs
  "Walk the captured trace events and build the `:sub-runs` vector.
  Each :sub/run trace event surfaces as one entry with `:recomputed?
  true`. Per Spec-Schemas §`:rf/epoch-record`: a sub queried via the
  rf2-719e cache hit path does NOT emit `:sub/run` (the body fn does
  not re-run), so cache-hit subs are absent from this projection."
  [events]
  (into []
        (comp
          (filter (fn [ev] (= :sub/run (:operation ev))))
          (map (fn [ev]
                 (let [t (:tags ev)]
                   {:sub-id      (:sub-id t)
                    :query-v     (:query-v t)
                    :recomputed? true}))))
        events))

(defn- project-renders
  "Walk the captured trace events and build the `:renders` vector.
  Renders are emitted by the view layer as `:view/render` trace events
  with `:render-key`, `:triggered-by`, and `:elapsed-ms` tags. Per
  Spec-Schemas §`:rf/epoch-record` and Spec 004 §Render-tree primitives
  (rf2-t5tx Option C / rf2-piag): `:render-key` is the tuple
  `[<view-id> <instance-token>]` — the view-id names the kind, the
  instance-token disambiguates concurrently-mounted instances. For
  renders that bypass reg-view (plain Reagent fns), the trace recorder
  emits `[:rf.view/anonymous nil]` as the documented fallback shape."
  [events]
  (into []
        (comp
          (filter (fn [ev]
                    (or (= :render/run (:operation ev))
                        (= :view/render (:operation ev)))))
          (map (fn [ev]
                 (let [t (:tags ev)]
                   {:render-key   (or (:render-key t)
                                      [:rf.view/anonymous nil])
                    :triggered-by (:triggered-by t)
                    :elapsed-ms   (:elapsed-ms t)}))))
        events))

(defn- project-effects
  "Walk the captured trace events and build the `:effects` vector.

  Per Spec-Schemas §`:rf/epoch-record` `:effects`: every dispatched fx
  surfaces one entry, regardless of outcome:

    :fx :rf.fx/handled                    → :outcome :ok
    :warning :rf.fx/skipped-on-platform   → :outcome :skipped-on-platform
    :error :rf.error/fx-handler-exception → :outcome :error
    :error :rf.error/no-such-fx           → :outcome :error

  The runtime emits exactly one of these per dispatched fx (see
  `re-frame.fx/handle-one-fx`), so the projection is one-entry-per-fx
  with no double-counting. `:error-trace` (when present) references
  the corresponding error trace event by `:id`."
  [events]
  (into []
        (comp
          (filter (fn [ev]
                    (let [op (:operation ev)]
                      (or (= :rf.fx/handled op)
                          (= :rf.fx/skipped-on-platform op)
                          (= :rf.error/fx-handler-exception op)
                          (= :rf.error/no-such-fx op)))))
          (map (fn [ev]
                 (let [op (:operation ev)
                       t  (:tags ev)]
                   (cond
                     (= :rf.fx/handled op)
                     {:fx-id   (:fx-id t)
                      :args    (:fx-args t)
                      :outcome :ok}

                     (= :rf.fx/skipped-on-platform op)
                     {:fx-id   (:fx-id t)
                      :args    (:fx-args t)
                      :outcome :skipped-on-platform}

                     (= :rf.error/fx-handler-exception op)
                     {:fx-id       (:fx-id t)
                      :args        (:fx-args t)
                      :outcome     :error
                      :error-trace (:id ev)}

                     (= :rf.error/no-such-fx op)
                     {:fx-id       (:fx-id t)
                      :args        (:fx-args t)
                      :outcome     :error
                      :error-trace (:id ev)})))))
        events))

;; ---- record assembly ------------------------------------------------------

(defonce ^:private epoch-counter (atom 0))

(defn- next-epoch-id []
  (swap! epoch-counter inc))

(defn- find-trigger-event
  "Walk the buffered events to find the first :event/run-start trace.
  That carries the `:event` and `:event-id` for the cascade.

  When the cascade had no successful event handler (e.g. an unknown
  event id or a frame-destroyed dispatch), no :run-start fires; fall
  back to the first event we can find with an `:event-id` tag.

  Per rf2-txrq9: single-walk reduction over `events` — the original
  two-pass `or`-of-`some` reordered both walks across the buffer
  on the degenerate path. We now accumulate the first
  `:event/run-start` AND the first fallback `:event-id` in one
  traversal and prefer the run-start. Either match short-circuits
  at the earliest moment it can — a run-start hit immediately
  reduces to the final result; a fallback-only stream walks once."
  [events]
  (let [result
        (reduce
          (fn [acc ev]
            (let [tags (:tags ev)]
              (if (and (= :event (:op-type ev))
                       (= :event (:operation ev))
                       (= :run-start (:phase tags)))
                ;; run-start beats the fallback; short-circuit.
                (reduced {:run-start {:event-id (:event-id tags)
                                      :event    (:event tags)}})
                ;; Capture the first :event-id we see as the fallback.
                (if (or (:fallback acc) (nil? (:event-id tags)))
                  acc
                  (assoc acc :fallback
                         (let [eid (:event-id tags)]
                           {:event-id eid
                            :event    (or (:event tags) [eid])}))))))
          {}
          events)]
    (or (:run-start result) (:fallback result))))

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

(defn- build-record
  [frame-id db-before db-after events]
  (let [{:keys [event-id event]} (find-trigger-event events)]
    {:epoch-id      (next-epoch-id)
     :frame         frame-id
     :committed-at  (interop/now-ms)
     :event-id      event-id
     :trigger-event event
     :db-before     db-before
     :db-after      db-after
     ;; Per Spec 010 §Schema digest — pinned at record time so a later
     ;; restore can compare 'recorded vs current' digests in the
     ;; :rf.epoch/restore-schema-mismatch trace tags. Optional per
     ;; Spec-Schemas §:rf/epoch-record (a host without a schema layer
     ;; produces nil; consumers tolerate the absent slot).
     :schema-digest (current-schema-digest frame-id)
     :trace-events  events
     :sub-runs      (project-sub-runs events)
     :renders       (project-renders events)
     :effects       (project-effects events)}))

;; ---- drain-settle hook ----------------------------------------------------

(defn settle!
  "Hook called by the router when a drain reaches an empty queue. Per
  Tool-Pair §Time-travel: 'every drain-settle (queue empty) appends an
  epoch-record. Atomic; partial drains don't record.'

  `db-before` is the app-db value snapshotted before the cascade began;
  `db-after` is the value after the drain settled. The captured trace
  buffer is harvested here and projected into the record."
  [frame-id db-before db-after]
  (when interop/debug-enabled?
    (let [events (harvest-buffer! frame-id)]
      ;; A truly empty cascade (no events captured for this frame) is
      ;; a degenerate case — likely a rejected dispatch. Skip recording
      ;; rather than emit a misleading record.
      (when (seq events)
        (let [record (build-record frame-id db-before db-after events)]
          (record! record)
          (trace/emit! :rf.epoch :rf.epoch/snapshotted
                       {:frame    frame-id
                        :epoch-id (:epoch-id record)
                        :event-id (:event-id record)})
          (notify-listeners! record))))))

(defn- discard-buffer!
  "Drop the in-flight capture buffer for frame-id. Used when a drain
  is aborted (e.g. depth-exceeded, frame destroyed mid-drain) to
  avoid leaking a partial buffer into the next cascade. No record
  is committed.

  Per rf2-hul9q: the only consumer is `router.cljc`'s drain-abort
  path via the `:epoch/discard-buffer!` late-bind hook (see the
  registration at the foot of this namespace). The fn itself takes
  no direct callers, so the visibility stays `defn-` to keep the
  late-bind seam the sole public access path — matches the
  `capture-event!` and `in-flight-buffer` pattern used elsewhere
  in this ns for hook-only producers."
  [frame-id]
  (when interop/debug-enabled?
    (harvest-buffer! frame-id))
  nil)

;; ---- restore failure-mode predicates --------------------------------------

(defn- malli-validate-fn
  "Return the malli validate fn or nil.

  Per rf2-t0hq the CLJS runtime `resolve` is a compile-time analyzer
  affordance, not a runtime fn — the historical `:cljs (resolve
  'malli.core/validate)` arm silently returned nil, so the
  schema-mismatch failure-mode predicate at epoch-restore time treated
  every recorded db as conforming on CLJS.

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

(defn- schema-validate-ok?
  "True when the recorded db validates against every app-schema
  registered against frame-id. Defensive: when no schemas are
  registered or Malli is not on the path, we consider the db valid
  (we can't disprove it)."
  [frame-id db]
  (let [schemas  (registered-app-schemas frame-id)
        validate (malli-validate-fn)]
    (or (empty? schemas)
        (nil? validate)
        (every? (fn [[path meta]]
                  (let [schema (:schema meta)
                        v      (get-in db path)]
                    (try (validate schema v)
                         (catch #?(:clj Throwable :cljs :default) _ true))))
                schemas))))

(defn- failing-paths-for [frame-id db]
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
  no-such-handler precondition-failure tuple. Returns `[:ok
  frame-record]` or `[:fail :rf.error/no-such-handler {:kind :frame
  :frame frame-id}]`. Shared by every Tool-Pair / time-travel write
  surface so the no-such-handler tag shape stays canonical."
  [frame-id]
  (if-let [frame-record (frame/frame frame-id)]
    [:ok frame-record]
    [:fail :rf.error/no-such-handler
     {:kind  :frame
      :frame frame-id}]))

(defn- check-restore-preconditions!
  "Validate the six documented preconditions for restoring `frame-id`
  to `epoch-id`. Returns:

    [:ok epoch]  — all checks passed; `epoch` is the resolved history
                   record whose `:db-after` is the restore target.
    [:fail kw tags]
                 — first failing check; `kw` is the trace operation
                   the caller must emit, `tags` are its tags. No
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
      (= :fail (first frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (second frame-result))
      [:fail :rf.epoch/restore-during-drain
       {:frame    frame-id
        :epoch-id epoch-id}]

      :else
      (let [history (epoch-history frame-id)
            epoch   (find-epoch-in history epoch-id)]
        (cond
          ;; (3) Epoch present in current history?
          (nil? epoch)
          [:fail :rf.epoch/restore-unknown-epoch
           {:frame        frame-id
            :epoch-id     epoch-id
            :history-size (count history)}]

          :else
          (let [db-target (:db-after epoch)]
            ;; Each helper is called once and its result bound, so the
            ;; failure path walks the recorded db / schema set / machine
            ;; map exactly once per check (rf2-081zk).
            (if-let [failing-paths (seq (failing-paths-for frame-id db-target))]
              ;; (4) Schema mismatch?
              ;; Per Spec 010 §Schema digest + Tool-Pair §Time-travel:
              ;; the trace carries both the digest pinned on the
              ;; epoch record (recorded) and the current frame's
              ;; live digest, so pair tools can pinpoint *what
              ;; changed* about the schema set, not merely *that*
              ;; it changed.
              [:fail :rf.epoch/restore-schema-mismatch
               {:frame                  frame-id
                :epoch-id               epoch-id
                :schema-digest-recorded (:schema-digest epoch)
                :schema-digest-current  (current-schema-digest frame-id)
                :failing-paths          (vec failing-paths)}]

              (if-let [missing (seq (missing-references db-target))]
                ;; (5) Missing handler referenced from db?
                [:fail :rf.epoch/restore-missing-handler
                 {:frame    frame-id
                  :epoch-id epoch-id
                  :missing  (vec missing)}]

                (if-let [{:keys [machine-id recorded current]} (machine-version-mismatch db-target)]
                  ;; (6) Machine snapshot version drift?
                  [:fail :rf.epoch/restore-version-mismatch
                   {:frame            frame-id
                    :epoch-id         epoch-id
                    :machine-id       machine-id
                    :version-recorded recorded
                    :version-current  current}]

                  [:ok epoch])))))))))

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
    :rf.epoch/restore-schema-mismatch  — db-after no longer validates
    :rf.epoch/restore-missing-handler  — referenced registration absent
    :rf.epoch/restore-version-mismatch — machine snapshot version drift

  Returns `true` on success, `false` on any failure."
  [frame-id epoch-id]
  (if-not interop/debug-enabled?
    false
    (let [result (check-restore-preconditions! frame-id epoch-id)]
      (case (first result)
        :ok   (perform-restore! frame-id (second result))
        :fail (do (emit-precondition-failure! (second result) (nth result 2))
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
  Returns `[:ok]` when all checks pass, otherwise `[:fail kw tags]`
  matching the precondition-failure shape of
  `check-restore-preconditions!`. Pure data — no trace events emitted
  from here; emission is the caller's job."
  [frame-id new-db]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (first frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (second frame-result))
      [:fail :rf.epoch/reset-frame-db-during-drain
       {:frame frame-id}]

      ;; (3) Schema mismatch?
      (not (schema-validate-ok? frame-id new-db))
      [:fail :rf.epoch/reset-frame-db-schema-mismatch
       {:frame         frame-id
        :failing-paths (failing-paths-for frame-id new-db)}]

      :else
      [:ok])))

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
    (let [record (assoc (build-record frame-id db-before new-db [])
                        :event-id      :rf.epoch/db-replaced
                        :trigger-event [:rf.epoch/db-replaced])]
      (record! record)
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
    (let [result (check-reset-frame-db-preconditions! frame-id new-db)]
      (case (first result)
        :ok   (perform-reset-frame-db! frame-id new-db)
        :fail (do (emit-precondition-failure! (second result) (nth result 2))
                  false)))))

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
(late-bind/set-fn! :epoch/in-flight-buffer    in-flight-buffer)
(late-bind/set-fn! :epoch/capture-event       capture-event!)
(late-bind/set-fn! :epoch/epoch-history       epoch-history)
(late-bind/set-fn! :epoch/restore-epoch       restore-epoch)
(late-bind/set-fn! :epoch/reset-frame-db!     reset-frame-db!)
(late-bind/set-fn! :epoch/register-epoch-cb!  register-epoch-cb!)
(late-bind/set-fn! :epoch/remove-epoch-cb!    remove-epoch-cb!)
(late-bind/set-fn! :epoch/configure!          configure!)
(late-bind/set-fn! :epoch/clear-history!      clear-history!)
(late-bind/set-fn! :epoch/clear-epoch-cbs!    clear-epoch-cbs!)
(late-bind/set-fn! :epoch/on-frame-destroyed  on-frame-destroyed!)
