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

(defn clear-frame-history!
  "Drop every recorded epoch for the named frame."
  [frame-id]
  (swap! histories dissoc frame-id)
  nil)

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))

(defn register-epoch-cb!
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. The id can be any comparable value; passing the
  same id twice replaces. Per Spec 009 §`register-epoch-cb` —
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
  id)

(defn remove-epoch-cb!
  "Remove the listener registered under id."
  [id]
  (swap! listeners dissoc id)
  nil)

(defn clear-epoch-cbs!
  []
  (reset! listeners {})
  nil)

(defn- notify-listeners! [record]
  (doseq [[_ f] @listeners]
    (try
      (f record)
      (catch #?(:clj Throwable :cljs :default) _
        ;; Per Spec 009 §Listener invocation rules: listener failures
        ;; are isolated. Continue notifying.
        nil))))

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
  tied to a specific cascade. The `:rf.epoch/*` trace events emitted by
  this namespace itself are also skipped, so a listener-driven cascade
  never feeds back into a capture buffer."
  [event]
  (when interop/debug-enabled?
    (let [op       (:operation event)
          tags     (:tags event)
          frame-id (or (:frame tags)
                       (:frame event))]
      (when (and frame-id
                 (not (contains? #{:rf.epoch/snapshotted
                                   :rf.epoch/restored
                                   :rf.epoch/restore-unknown-epoch
                                   :rf.epoch/restore-schema-mismatch
                                   :rf.epoch/restore-missing-handler
                                   :rf.epoch/restore-version-mismatch
                                   :rf.epoch/restore-during-drain}
                                 op)))
        (buffer-event! frame-id event)))))

;; ---- record projection ----------------------------------------------------

(defn- project-sub-runs
  "Walk the captured trace events and build the `:sub-runs` vector.
  Each :sub/run trace event surfaces as one entry with `:recomputed?
  true`. Per Spec-Schemas §`:rf/epoch-record`: a sub queried via the
  rf2-719e cache hit path does NOT emit `:sub/run` (the body fn does
  not re-run), so cache-hit subs are absent from this projection.
  `:result-changed?` is currently true when the sub recomputed (the
  raw trace doesn't yet carry the prior value); tools requiring
  fine-grained change-tracking should consume the raw trace stream
  until rf2 wires post-compute equality back through the trace."
  [events]
  (into []
        (comp
          (filter (fn [ev] (= :sub/run (:operation ev))))
          (map (fn [ev]
                 (let [t (:tags ev)]
                   {:sub-id          (:sub-id t)
                    :query-v         (:query-v t)
                    :recomputed?     true
                    :result-changed? true}))))
        events))

(defn- project-renders
  "Walk the captured trace events and build the `:renders` vector.
  Renders are emitted by the view layer as `:render/run` (or similar)
  trace events with `:render-key`, `:triggered-by`, and `:elapsed-ms`
  tags. Per Spec-Schemas §`:rf/epoch-record`: `:render-key` identity
  is TBD pending rf2-t5tx — treated as opaque.

  No render trace is emitted by the runtime today; this projection
  remains an empty vector until the view layer is wired to emit
  per-render traces. The shape is contracted now so consumers can
  rely on the slot existing."
  [events]
  (into []
        (comp
          (filter (fn [ev]
                    (or (= :render/run (:operation ev))
                        (= :view/render (:operation ev)))))
          (map (fn [ev]
                 (let [t (:tags ev)]
                   {:render-key   (:render-key t)
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
  back to the first event we can find with an `:event-id` tag."
  [events]
  (or
    (some (fn [ev]
            (when (and (= :event (:op-type ev))
                       (= :event (:operation ev))
                       (= :run-start (get-in ev [:tags :phase])))
              {:event-id (get-in ev [:tags :event-id])
               :event    (get-in ev [:tags :event])}))
          events)
    (some (fn [ev]
            (when-let [eid (get-in ev [:tags :event-id])]
              {:event-id eid
               :event    (or (get-in ev [:tags :event])
                             [eid])}))
          events)))

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

(defn discard-buffer!
  "Drop the in-flight capture buffer for frame-id. Used when a drain
  is aborted (e.g. depth-exceeded, frame destroyed mid-drain) to
  avoid leaking a partial buffer into the next cascade. No record
  is committed."
  [frame-id]
  (when interop/debug-enabled?
    (harvest-buffer! frame-id))
  nil)

;; ---- restore failure-mode predicates --------------------------------------

(defn- malli-validate-fn
  "Return the malli validate fn (resolved at call site) or nil."
  []
  #?(:clj  (try (requiring-resolve 'malli.core/validate)
                (catch Throwable _ nil))
     :cljs (try (resolve 'malli.core/validate)
                (catch :default _ nil))))

(defn- registered-app-schemas []
  (registrar/handlers :app-schema))

(defn- schema-validate-ok?
  "True when the recorded db validates against every currently-registered
  app-schema. Defensive: when no schemas are registered or Malli is
  not on the path, we consider the db valid (we can't disprove it)."
  [db]
  (let [schemas  (registered-app-schemas)
        validate (malli-validate-fn)]
    (or (empty? schemas)
        (nil? validate)
        (every? (fn [[_path meta]]
                  (let [schema (:schema meta)
                        path   (:path meta)
                        v      (get-in db path)]
                    (try (validate schema v)
                         (catch #?(:clj Throwable :cljs :default) _ true))))
                schemas))))

(defn- failing-paths-for [db]
  (let [schemas  (registered-app-schemas)
        validate (malli-validate-fn)]
    (if (or (empty? schemas) (nil? validate))
      []
      (vec
        (keep (fn [[_id meta]]
                (let [schema (:schema meta)
                      path   (:path meta)
                      v      (get-in db path)]
                  (when-not (try (validate schema v)
                                 (catch #?(:clj Throwable :cljs :default) _ true))
                    path)))
              schemas)))))

(defn- missing-references
  "Walk the recorded db for ids that are no longer present in the
  registrar. Closed v1 surface — `:rf/machines` (machine-id keys must
  reference a registered :head/machine snapshot via the machine
  registry) and `:route` (`:id` must reference a registered :route).

  Returns a vector of {:kind <kind> :id <id>} entries. Empty when
  every reference resolves."
  [db]
  (let [missing (atom [])]
    ;; Machines under :rf/machines: re-frame.machines registers under :head.
    (doseq [[machine-id _snapshot] (:rf/machines db)]
      (when-not (registrar/lookup :head machine-id)
        (swap! missing conj {:kind :head :id machine-id})))
    ;; Active route
    (when-let [route-id (get-in db [:route :id])]
      (when-not (registrar/lookup :route route-id)
        (swap! missing conj {:kind :route :id route-id})))
    @missing))

(defn- machine-version-mismatch
  "Walk the recorded db's `:rf/machines` for snapshot version drift.
  Each snapshot may carry `:rf/snapshot-version`; the currently-
  registered machine carries `:version` in its meta. When they
  differ, return the first mismatch as
  `{:machine-id <id> :recorded <int> :current <int>}`. nil when no
  mismatch is found."
  [db]
  (some (fn [[machine-id snapshot]]
          (let [recorded (:rf/snapshot-version snapshot)]
            (when (some? recorded)
              (let [meta    (registrar/lookup :head machine-id)
                    current (:version meta)]
                (when (and (some? current) (not= recorded current))
                  {:machine-id machine-id
                   :recorded   recorded
                   :current    current})))))
        (:rf/machines db)))

;; ---- restore --------------------------------------------------------------

(defn- find-epoch
  [frame-id epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
        (epoch-history frame-id)))

(defn- emit-restore-failure!
  [operation tags]
  (trace/emit-error! operation
                     (assoc tags :recovery :no-recovery)))

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
    (let [frame-record (frame/frame frame-id)]
      (cond
        ;; (1) Frame registered?
        (nil? frame-record)
        (do
          (emit-restore-failure! :rf.error/no-such-handler
                                 {:kind     :frame
                                  :frame-id frame-id})
          false)

        ;; (2) In-flight drain?
        (let [router (:router frame-record)
              r      (when router @router)]
          (and r (or (:in-drain? r) (:in-sync-drain? r))))
        (do
          (emit-restore-failure! :rf.epoch/restore-during-drain
                                 {:frame    frame-id
                                  :epoch-id epoch-id})
          false)

        :else
        (let [history (epoch-history frame-id)
              epoch   (find-epoch frame-id epoch-id)]
          (cond
            ;; (3) Epoch present in current history?
            (nil? epoch)
            (do
              (emit-restore-failure! :rf.epoch/restore-unknown-epoch
                                     {:frame        frame-id
                                      :epoch-id     epoch-id
                                      :history-size (count history)})
              false)

            :else
            (let [db-target (:db-after epoch)]
              (cond
                ;; (4) Schema mismatch?
                (not (schema-validate-ok? db-target))
                (do
                  (emit-restore-failure! :rf.epoch/restore-schema-mismatch
                                         {:frame                  frame-id
                                          :epoch-id               epoch-id
                                          :schema-digest-recorded nil
                                          :schema-digest-current  nil
                                          :failing-paths          (failing-paths-for db-target)})
                  false)

                ;; (5) Missing handler referenced from db?
                (let [missing (missing-references db-target)]
                  (seq missing))
                (do
                  (emit-restore-failure! :rf.epoch/restore-missing-handler
                                         {:frame    frame-id
                                          :epoch-id epoch-id
                                          :missing  (missing-references db-target)})
                  false)

                ;; (6) Machine snapshot version drift?
                (let [m (machine-version-mismatch db-target)]
                  (some? m))
                (let [{:keys [machine-id recorded current]} (machine-version-mismatch db-target)]
                  (emit-restore-failure! :rf.epoch/restore-version-mismatch
                                         {:frame            frame-id
                                          :epoch-id         epoch-id
                                          :machine-id       machine-id
                                          :version-recorded recorded
                                          :version-current  current})
                  false)

                :else
                (let [container (frame/get-frame-db frame-id)]
                  (adapter/replace-container! container db-target)
                  (trace/emit! :rf.epoch :rf.epoch/restored
                               {:frame    frame-id
                                :epoch-id epoch-id})
                  true)))))))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; The router calls into settle! at drain-empty. Publishing through the
;; late-bind registry keeps router.cljc free of a require on this ns.

(late-bind/set-fn! :epoch/settle!          settle!)
(late-bind/set-fn! :epoch/discard-buffer!  discard-buffer!)
(late-bind/set-fn! :epoch/in-flight-buffer in-flight-buffer)
(late-bind/set-fn! :epoch/capture-event    capture-event!)
