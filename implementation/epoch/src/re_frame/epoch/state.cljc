(ns re-frame.epoch.state
  "Private storage and low-level state operations for epoch history.

  State is split by access pattern:

    config                  per-frame ring-buffer config + redact-fn
    epoch-counter           monotonically-increasing :epoch-id source
    histories               frame-id → vector<:rf/epoch-record>
    last-settled-epoch      frame-id → epoch-id of the most-recently-
                            settled epoch (async back-fill anchor)
    mount-attribution       frame-id → {render-key → {:epoch-id E
                                                       :deps #{sub-id…}}}
                            (mount anchor and learned view read-set)
    capture-buffers         frame-id → vector<trace-event> (in-flight)
    listeners               cb-id    → {:generation token :callback fn}
    observed-frames-by-cb   cb-id    → {frame-id → generation-token}

  Capture buffers stay separate from rarely-written back-fill state because
  every trace emit updates them. Listener observations likewise stay separate
  from the registry snapshot read on every fan-out; they cohere through the
  per-registration GENERATION token (see the listener-registry section) rather
  than a cross-atom update, so no operation requires an atomic update across two
  of these stores."
  (:require [re-frame.privacy :as privacy]))

;; ---- configuration --------------------------------------------------------

(def ^:private default-depth
  ;; Deep enough to hold a typical debug session's cascade history;
  ;; trades bounded heap for stable time-travel coverage.
  50)

(def ^:private default-trace-events-keep
  ;; Matches `default-depth` so by default trace is retained for every
  ;; retained epoch: record and trace evict together. Hosts may configure a
  ;; lower cap; zero drops the raw trace from every stored record.
  50)

(def ^:private default-config
  ;; `:redact-fn` is an optional projection-side override, not a storage hook.
  {:depth             default-depth
   :trace-events-keep default-trace-events-keep
   :redact-fn         nil})

(defonce ^:private config
  (atom default-config))

(defn non-neg-int?
  "True for non-negative integer values; nil and non-numeric values
  fail. Mirrors the validation `re-frame.trace/configure-trace-buffer!`
  applies at its own config boundary."
  [x]
  (and (integer? x) (not (neg? x))))

(defn merge-config!
  "Validate and merge an `opts` map into the live config atom. Returns
  nil. Silently drops invalid slot values (`:depth` /
  `:trace-events-keep` must be non-negative integers; `:redact-fn`
  accepts `fn?` or `nil` for explicit-clear; anything else is dropped).

  Validation at this boundary keeps numeric assumptions out of the hot path."
  [opts]
  (when (map? opts)
    (let [numeric (select-keys opts [:depth :trace-events-keep])
          numeric-valid (into {}
                              (filter (fn [[_ v]] (non-neg-int? v)))
                              numeric)
          ;; :redact-fn validated separately — accept fn? OR nil
          ;; (explicit-clear); anything else silently dropped.
          ;; `contains?` distinguishes 'absent slot' from 'present
          ;; nil' so the explicit-clear path lands while a callsite
          ;; that didn't mention :redact-fn doesn't clobber a
          ;; previously-installed fn.
          redact (when (contains? opts :redact-fn)
                   (let [v (:redact-fn opts)]
                     (when (or (nil? v) (fn? v))
                       {:redact-fn v})))
          valid (merge numeric-valid redact)]
      (when (seq valid)
        (swap! config merge valid))))
  nil)

(defn current-config
  "Return the current epoch-history configuration map."
  []
  @config)

(defn reset-config!
  "Restore the live config atom to the shipped `default-config`
  baseline (`:depth` 50, `:trace-events-keep` 50, `:redact-fn` nil).
  Replacing the whole map ensures test fixtures also clear an installed
  projection override. Returns nil."
  []
  (reset! config default-config)
  nil)

(defn depth []
  (:depth @config default-depth))

(defn trace-events-keep []
  (:trace-events-keep @config default-trace-events-keep))

(defn redact-fn
  "Return the currently-installed `:redact-fn` (or nil). One config
  deref per record-build — the hot path for installed-fn cases is one
  keyword lookup, no allocation."
  []
  (:redact-fn @config))

;; ---- epoch-id counter -----------------------------------------------------

(defonce ^:private epoch-counter (atom 0))

(defn next-epoch-id []
  (swap! epoch-counter inc))

;; ---- per-frame ring buffer ------------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history": last N epochs per frame.
;; Stored as a map of frame-id → vector (oldest-first). New records append
;; to the back; the front evicts when the buffer exceeds the configured
;; depth.

(defonce ^:private histories (atom {}))

(defn- elide-just-crossed-trace-events
  "When the record at index `(- (count history) keep 1)` crosses the
  keep-boundary, dissoc its `:trace-events`. O(1) per append: every
  earlier record was already elided on its own crossing, so the only
  record that needs work is the one that just slid out of the keep-
  window. Records keep their structured projections (`:sub-runs` /
  `:renders` / `:effects`) but lose the raw trace stream. nil `keep`
  means 'keep every record's :trace-events'.

  Invoked from `append-record` for every event epoch. Under steady state only
  one record per append
  actually transitions out of the keep-window, so touching just that
  record (rather than walking the whole history vector) is all that is
  needed. The steady-state invariant holds because every prior append
  already elided its own just-crossed record; runtime reductions of `keep`
  via `(rf/configure! {:epoch-history ...})` will take full effect on
  subsequent appends rather than retroactively rewriting the buffer."
  [history keep]
  (let [n (count history)]
    (if (and (some? keep) (nat-int? keep) (> n keep))
      (let [idx    (- n keep 1)
            record (nth history idx)]
        (if (contains? record :trace-events)
          (assoc history idx (dissoc record :trace-events))
          history))
      history)))

(defn- append-record
  "Conj `record` onto the frame's history vector, cap to `d` by
  MATERIALISING the most-recent-`d` window into a fresh vector, then
  elide the just-crossed record's `:trace-events` per `keep`.

  The cap MUST materialise — a bare `(subvec history+ ...)` view does
  NOT release the evicted records. `SubVector.cons` keeps appending to
  the SAME growing underlying vector and `subvec` of a `SubVector`
  re-wraps that same backing vector, so over a long session the
  depth-`d` view's backing PersistentVector accretes every record ever
  appended (each carrying its full `:db-before` / `:db-after` /
  `:trace-events` payload) — an unbounded heap leak that defeats the
  bounded-ring contract even though `history-for` correctly returns
  only `d` records. `(into [] (subvec ...))` copies the `d`-element
  window into a concrete PersistentVector whose backing is exactly `d`,
  so the evicted records become GC-eligible.

  HOT PATH: fires once per cascade settle, i.e. once per dispatched
  user event under steady state. Below the cap the append is O(1) (a
  plain `conj`); once the ring is full each append is O(d) — a `d`-wide
  copy of the retained window (d defaults to 50, fired once per
  user-facing event, not per trace emit). The bounded copy is the
  necessary cost of bounded heap; the prior O(1) `subvec` view was O(1)
  in time but O(session-length) in retained heap. The trace-events
  elision stays O(1) — at most one record's `:trace-events` slot is
  dissoc'd."
  [history record d keep]
  (let [history+ (conj (or history []) record)
        n        (count history+)
        capped   (if (and (pos? d) (> n d))
                   (into [] (subvec history+ (- n d)))
                   history+)]
    (elide-just-crossed-trace-events capped keep)))

(defn record!
  "Append a record into the frame's history. The depth cap and the
  `:trace-events-keep` cap are read from the config atom on each
  append so runtime `(rf/configure! {:epoch-history ...})` takes effect
  immediately."
  [record]
  (let [d    (depth)
        keep (trace-events-keep)]
    (when (pos? d)
      (let [frame-id (:frame record)]
        (swap! histories update frame-id append-record record d keep)))))

(defn history-for
  "Return the frame's history vector (oldest-first) or `[]`."
  [frame-id]
  (or (get @histories frame-id) []))

(defn drop-frame-history!
  "Drop the named frame's ring buffer."
  [frame-id]
  (swap! histories dissoc frame-id)
  nil)

(defn- epoch-index
  "Return the index of the record matching `epoch-id` in the `history`
  vector, or nil when absent (evicted, or the epoch never landed). The
  one shared 'where is this epoch in the ring?' primitive — the
  back-fills `assoc` at the returned index, and `render-key-already-in-
  epoch?` reuses it for its record lookup. `tool-pair/find-epoch-in` answers
  the sibling 'give me the record' question off a deref'd history."
  [history epoch-id]
  (some (fn [i]
          (when (= epoch-id (:epoch-id (nth history i)))
            i))
        (range (count history))))

;; ---- post-settle render back-fill -----------------------------------------
;;
;; A `:view/render` / `:rf.view/rendered` trace fires at React COMMIT
;; time, which lands AFTER the causing cascade's run-to-completion has
;; settled (Reagent batches re-renders onto a later tick — see
;; `re-frame.interop/after-render`). By commit time `settle!` has already
;; harvested the cascade buffer and committed the record, so the render
;; emit lands in a now-empty buffer; left to the ordinary harvest it would
;; be harvested by the next event and misattributed by one epoch.
;;
;; Instead the render is attributed to the cascade that CAUSED it: when a
;; render fires with no in-flight cascade for the frame, it is back-filled
;; into that frame's most-recently-settled epoch record (the cascade that
;; dirtied the view's inputs and scheduled the re-render). `last-settled-
;; epoch` tracks frame-id → epoch-id so the back-fill targets the right
;; record without re-walking the whole ring.

(defonce ^:private last-settled-epoch
  ;; frame-id → epoch-id of the most-recently-committed :ok-shaped epoch.
  (atom {}))

(defn set-last-settled-epoch!
  "Record `epoch-id` as the most-recently-settled epoch for `frame-id`.
  Called from `settle!` after the record lands in the ring so post-settle
  render emits can be attributed back to this cascade.

  Gated on a positive depth. The anchor
  is the back-fill target the post-settle render / sub-run / unmount readers
  resolve (`last-settled-epoch-id`); it must only ever name an epoch that is
  actually retained in the ring. Under `(rf/configure! {:epoch-history
  {:depth 0}})` the ring is disabled and `record!` appends nothing, so an
  anchor set here would be a PHANTOM — it would name a non-ring epoch-id and
  the back-fill splice would resolve a `nil` index (no-op) or, worse, a stale
  one. Gating here keeps the invariant central across all three callers
  (`commit-record!`, the synthetic-replace recorder, and `perform-restore!`'s
  re-anchor): no anchor without a ring record to anchor to. The readers all
  `when-let` off `last-settled-epoch-id`, so a nil anchor degrades cleanly to
  no back-fill — correct under depth 0."
  [frame-id epoch-id]
  (when (and frame-id epoch-id (pos? (depth)))
    (swap! last-settled-epoch assoc frame-id epoch-id))
  nil)

(defn last-settled-epoch-id
  "Return the most-recently-settled epoch-id for `frame-id`, or nil."
  [frame-id]
  (get @last-settled-epoch frame-id))

(defn drop-last-settled-epoch!
  "Forget the most-recently-settled epoch for `frame-id` (frame teardown
  / fixture reset)."
  [frame-id]
  (swap! last-settled-epoch dissoc frame-id)
  nil)

(defn reset-last-settled-epochs!
  "Wipe the last-settled-epoch map across all frames. Test fixtures call
  this in lockstep with `reset-histories!` / `reset-capture-buffers!`."
  []
  (reset! last-settled-epoch {})
  nil)

;; ---- mount-epoch tracking + render-attribution resolution -----------------
;;
;; Post-settle rendering normally back-fills to the frame's most-recently-
;; settled epoch — the cascade that ran just before the React commit. That
;; is correct for a genuine reactive re-render, but WRONG for a MOUNT
;; render whose commit lands late: React/Reagent batch a freshly-mounted
;; component's render onto a later tick, so a view's mount render can
;; commit AFTER the first user interaction settles. Attributed to
;; last-settled, it shows up spuriously in the first post-mount cascade's
;; RENDERED list (e.g. parallel-frames' title-view appearing in the first
;; counter-inc epoch even though its subs never changed).
;;
;; The discriminator combines the `:value-changed?` attribution on the
;; reactive `:sub/run` trace with the `:reader-render-key` naming the view that
;; sub. A render of view K belongs to epoch N iff N's cascade actually
;; drove K's re-render — i.e. some `:sub/run` with `:reader-render-key K`
;; AND `:value-changed? true` landed in N. When no recent epoch shows a
;; value-change for K, the render is a mount (or mount-burst tail) and is
;; attributed to K's MOUNT epoch (its first-ever attribution) rather than
;; whatever cascade happens to be settling.

;; Per-entry shape:
;;
;;   {frame-id {render-key {:epoch-id <epoch-id-or-nil>
;;                          :deps     #{<sub-id> ...}}}}
;;
;; Either slot may be absent on a given (frame, render-key) entry — the
;; mount-anchor lands on first render attribution, the read-set lands as
;; the synchronous in-render derefs fire. Reads default to nil / empty;
;; writes use `update-in` so a missing entry materialises on demand.

(defonce ^:private mount-attribution (atom {}))

(defn record-render-deps!
  "Union `sub-id` into `render-key`'s read-set for `frame-id`. Called for
  every `:sub/run` that arrives stamped with a `:reader-render-key`
  by the synchronous in-render deref that names the reading view.
  Idempotent; the set only grows."
  [frame-id render-key sub-id]
  (when (and frame-id render-key sub-id)
    (swap! mount-attribution
           update-in [frame-id render-key :deps] (fnil conj #{}) sub-id))
  nil)

(defn render-deps-for
  "Return the set of sub-ids `render-key` is known to read in `frame-id`,
  or nil when none learned yet."
  [frame-id render-key]
  (get-in @mount-attribution [frame-id render-key :deps]))

(defn mount-epoch-for
  "Return the epoch-id `render-key` was first attributed to in `frame-id`,
  or nil when never seen."
  [frame-id render-key]
  (get-in @mount-attribution [frame-id render-key :epoch-id]))

(defn record-mount-epoch!
  "Record `epoch-id` as `render-key`'s mount epoch for `frame-id`, but
  only on the FIRST sighting — later attributions never overwrite the
  mount epoch (a re-render of an existing instance must not move its
  mount anchor)."
  [frame-id render-key epoch-id]
  (when (and frame-id render-key epoch-id)
    (swap! mount-attribution
           (fn [m]
             (if (get-in m [frame-id render-key :epoch-id])
               m
               (assoc-in m [frame-id render-key :epoch-id] epoch-id)))))
  nil)

(defn drop-render-key-mount-attribution!
  "Forget the mount-anchor + read-set for a SINGLE `render-key` in
  `frame-id` — the per-instance eviction tied to a view
  instance's UNMOUNT. Without it `mount-attribution` accreted one
  permanent entry per ever-mounted instance (a fresh `instance-token`
  per mount → a fresh render-key), pruned ONLY on whole-frame destroy:
  unbounded per-frame heap growth over a long churning session (lists
  with churning rows, modals, route-scoped components — exactly the
  long-running time-travel scenario this surface exists to serve). The
  per-instance peer of `drop-frame-mount-attribution!`'s whole-frame
  wipe; called from `record-unmount!` after the trace back-fill.

  Drops the now-empty frame map when this was the frame's last
  render-key so a churned-then-idle frame leaves NO residual `{frame
  {}}` shell. Idempotent: dropping an absent render-key is a no-op. A
  late mount-burst tail render arriving AFTER the unmount (pathological
  ordering) harmlessly re-mints the entry via `record-mount-epoch!` /
  `record-render-deps!`."
  [frame-id render-key]
  (when (and frame-id render-key)
    (swap! mount-attribution
           (fn [m]
             (let [pruned (update m frame-id dissoc render-key)]
               (if (empty? (get pruned frame-id))
                 (dissoc pruned frame-id)
                 pruned)))))
  nil)

(defn drop-frame-mount-attribution!
  "Forget every render-key's mount-anchor + read-set for `frame-id`
  during frame teardown."
  [frame-id]
  (swap! mount-attribution dissoc frame-id)
  nil)

(defn reset-mount-attribution!
  "Wipe the mount-attribution map across all frames for fixture reset."
  []
  (reset! mount-attribution {})
  nil)

(defn- epoch-value-changed-for-view?
  "True when epoch `record` carries a value-changed `:sub/run` belonging
  to the view named by `render-key`.

  Two evidence sources, in priority order:

    1. The raw `:trace-events` (when retained — within `:trace-events-keep`):
       a `:sub/run` with `:value-changed? true` whose `:reader-render-key` is
       `render-key` (the synchronous in-render deref, when stamped) OR whose
       `:sub-id` is in the view's learned read-set `deps` (the post-settle
       reactive recompute, which carries no render-key). This is the richer
       source — it can match by render-key even before the read-set is learned.

    2. The structured `:sub-runs` projection — consulted only when
       the record's raw `:trace-events` were elided (the `:trace-events-keep 0`
       memory/privacy posture drops every record's raw stream while RETAINING
       the structured rows). A `:sub-runs` row carries `:value-changed?` and
       `:sub-id` but NO `:reader-render-key` (see `capture/sub-run-row`), so the
       structured match is `deps`-only: a value-changed row whose `:sub-id` is in
       the view's learned read-set. Without this, render attribution under
       keep-0 saw NO value-change evidence and mis-recorded genuine re-renders
       against the mount/default epoch.

  `deps` may be nil when the view's read-set was never learned — then only the
  render-key (trace) match applies, and the structured fallback yields nothing.
  Prefers the trace source when present so the render-key precision is kept;
  falls back to the structured rows only when traces are absent."
  [record render-key deps]
  (if (contains? record :trace-events)
    (some (fn [ev]
            (and (= :rf.sub/run (:operation ev))
                 (true? (-> ev :tags :rf.sub/value-changed?))
                 (let [tags (:tags ev)]
                   (or (= render-key (:rf.sub/reader-render-key tags))
                       (and deps (contains? deps (:rf.sub/id tags)))))))
          (:trace-events record))
    ;; `:trace-events` elided (keep-0, or this record below the
    ;; elision boundary). The structured `:sub-runs` rows are retained for
    ;; every record; match a value-changed row by the view's learned read-set.
    (and deps
         (some (fn [row]
                 (and (true? (:value-changed? row))
                      (contains? deps (:sub-id row))))
               (:sub-runs record)))))

(defn- value-changed-epoch-for
  "Scan `frame-id`'s ring (from the last-settled anchor, newest-first) for
  the newest epoch AT OR BEFORE the anchor in which the view named by
  `render-key` had a value-changed `:sub/run` — i.e. the cascade that
  genuinely re-rendered the view. Returns that epoch-id, or nil when no
  retained epoch at or before the anchor shows a value-change for the
  view (a mount / mount-burst tail).

  The scan starts at `anchor-epoch-id` (the most-recently-settled epoch —
  `resolve-render-epoch`'s `default`), so records NEWER than the anchor are
  NEVER considered. In normal operation the anchor IS the ring-newest record,
  so the start index is `(dec n)` and the bound is a no-op. After a time-travel
  restore `perform-restore!` re-anchors last-settled to an OLDER epoch WITHOUT
  truncating the ring (rf2-arzb9o), so the newer pre-restore epochs remain —
  and they still carry value-changed sub-run evidence for a repainted view. An
  UNBOUNDED newest-first scan would return one of those STALE newer epochs,
  attributing a POST-restore repaint to a PRE-restore cascade and silently
  defeating the restore re-anchor (the render-path sibling of the rf2-w4q9gt
  back-fill re-anchor). Bounding the scan at the anchor index confines
  attribution to the restored timeline. When the anchor is nil or evicted the
  bound degrades to the ring-newest index (`(dec n)`) — there is no bound to
  apply.

  A view's subs are matched (see `epoch-value-changed-for-view?`): by the
  `:reader-render-key` stamp (synchronous in-render deref, raw-trace only) and
  by the view's learned read-set (`render-deps-for`) for post-settle reactive
  recomputes that carry no render-key. The read-set is learned at mount from
  the stamped synchronous derefs; a view's sub set is stable across its life,
  so mount-time learning suffices.

  Evidence window: each record is matched against its raw
  `:trace-events` when retained (within `:trace-events-keep`), else against its
  structured `:sub-runs` projection — which is retained for EVERY record
  regardless of `:trace-events-keep`. The scan therefore reaches index 0:

    * keep > 0 — the genuine re-render rides one of the most-recent retained
      cascades, so the newest-first scan short-circuits on the first hit; the
      common mount / mount-burst-tail miss still walks at most the trace window
      plus the structured tail. The raw-trace records are matched with full
      render-key precision; older trace-elided records fall back to the
      `:sub-runs` `deps`-only match (harmless — they predate the re-render).

    * keep = 0 — no record retains raw traces, so every record is
      matched via its structured `:sub-runs`. The scan must NOT break at the
      first trace-elided record (the NEWEST one under keep-0): a genuine
      value-changing sub-run sitting in the newest epoch's `:sub-runs` must
      still be seen, or the render would be mis-attributed to the
      mount/default epoch. Consulting `:sub-runs` lets the newest-first scan
      find it and short-circuit on the current epoch.

  The scan never treats the elision boundary as a hard stop: a trace-elided
  record can still match via its structured
  `:sub-runs` fallback. Under keep-0 the scan is O(depth) to a
  miss; that is the necessary cost of attribution when no trace window exists,
  and it still short-circuits on the first (newest) value-change for the common
  genuine-re-render case. One pass newest-first from the anchor;
  short-circuits at the first matching epoch."
  [frame-id render-key anchor-epoch-id]
  (let [history (history-for frame-id)
        deps    (render-deps-for frame-id render-key)
        n       (count history)
        ;; Records newer than the anchor exist only after a restore rewind;
        ;; start the newest-first scan at the anchor so they are excluded.
        start   (or (epoch-index history anchor-epoch-id) (dec n))]
    (loop [i start]
      (when (>= i 0)
        (let [record (nth history i)]
          (if (epoch-value-changed-for-view? record render-key deps)
            (:epoch-id record)
            (recur (dec i))))))))

(defn resolve-render-epoch
  "Resolve the epoch a post-settle render of `render-key` should be
  attributed to in `frame-id`. Falls back to `default-epoch-id`, the most
  recently settled epoch, when no better anchor is found.

  Resolution order:
    1. The newest epoch AT OR BEFORE the anchor in which the view's OWN
       inputs changed (`value-changed-epoch-for`, bounded at
       `default-epoch-id`) — a genuine reactive re-render rides its causing
       cascade. The anchor bound keeps a post-restore repaint on the restored
       timeline rather than attributing it to a stale newer pre-restore epoch
       (rf2-arzb9o).
    2. Otherwise the view's MOUNT epoch (`mount-epoch-for`) — a mount
       render, or a mount-burst tail that re-deref'd unchanged subs, is
       anchored to where the instance first rendered rather than leaking
       into the first post-mount cascade.
    3. Otherwise `default-epoch-id` — a brand-new instance whose very
       first render commits post-settle (no mount epoch recorded yet, no
       value-change scan hit): treat the settling cascade as its mount,
       which `record-mount-epoch!` then anchors."
  [frame-id render-key default-epoch-id]
  (or (when render-key (value-changed-epoch-for frame-id render-key default-epoch-id))
      (when render-key (mount-epoch-for frame-id render-key))
      default-epoch-id))

(defn render-key-already-in-epoch?
  "True when `frame-id`'s epoch `epoch-id` record already carries a
  `:renders` row for `render-key` — used to de-dup a mount-burst tail
  that resolves back to the mount epoch where the render already landed.
  Reads the structured `:renders` projection (always
  present on a built record), not the optional `:trace-events`."
  [frame-id epoch-id render-key]
  (let [history (history-for frame-id)
        idx     (epoch-index history epoch-id)]
    (boolean
      (when idx
        (some (fn [row] (= render-key (:render-key row)))
              (:renders (nth history idx)))))))

;; ---- post-settle event back-fill ------------------------------------------
;;
;; A `:view/render` and a reactive `:sub/run` both
;; fire at React COMMIT / DEREF time, which Reagent batches onto a later
;; tick AFTER the causing cascade settled. So each lands in the now-empty
;; capture buffer post-settle; left to the ordinary harvest it would be
;; harvested by the next event and misattributed to epoch N+1
;; (a one-epoch lag: a phantom render in the wrong cascade's `:renders`, a
;; phantom sub-run + `:value-changed?` in the wrong cascade's `:sub-runs` /
;; Xray Views subs table).
;;
;; Instead the event is back-filled into the cascade that CAUSED it (the
;; frame's most-recently-settled epoch — the cascade that dirtied the
;; view's / reaction's inputs and scheduled the re-render / recompute),
;; reusing the `last-settled-epoch` map. The render and sub-run paths are
;; byte-for-byte identical except for the structured projection slot they
;; touch (`:renders` vs `:sub-runs`), so one private `back-fill-event!`
;; does the ring mutation and the two public fns are thin wrappers
;; pinning the slot.

(defn- back-fill-event!
  "Append a raw post-settle event and optional structured row to an existing
  epoch. Returns the updated record for listener re-notification, or nil when
  the target was evicted or never stored.

  Raw trace is appended only when that record retained `:trace-events`; a
  non-nil row is appended independently to `slot`. Sensitive evidence is ORed
  into the record-level rollup. Projection never runs on this storage path.

  Back-fill runs outside the frame drain and can race ring append/eviction.
  Therefore the epoch index must be resolved inside the CAS-retried `swap!`
  update from the same history map being rewritten. Resolving it earlier could
  splice the wrong record after a capped-ring eviction shifts indices. The
  update function remains pure so retries are safe."
  [frame-id epoch-id slot event row]
  (let [;; The record-level rollup must include post-settle trace evidence,
        ;; but `build-record` computes it ONCE at settle time over the
        ;; SETTLE-TIME events; a post-settle back-fill of a `:sensitive?`-
        ;; stamped trace event would otherwise leave the rollup stale-false.
        ;; `privacy/sensitive?` is pure and depends only on `event` (not on any
        ;; @histories snapshot), so it is hoisted out of the swap; the swap fn
        ;; ORs it into the rollup fail closed. The OR is
        ;; monotonic/idempotent (only flips false→true, never clears a
        ;; settle-time true), so it rides safely inside the CAS-retried swap.
        sens?   (privacy/sensitive? event)
        row?    (some? row)
        splice-slot (fn [rec real-slot dv]
                      (cond
                        (vector? (get rec real-slot))
                        (update rec real-slot conj dv)

                        (not (contains? rec real-slot))
                        (assoc rec real-slot [dv])

                        :else            ; real slot already a scalar
                        rec))            ; delta subsumed
        ;; Operate on the whole histories map so the index is
        ;; re-derived from the SAME (CAS-retried) value the splice rewrites —
        ;; never against a separate, possibly-shifted deref. When the epoch is
        ;; no longer in the frame's ring (evicted, or never landed) the map is
        ;; returned unchanged and the post-swap read-back yields nil.
        splice-map (fn [m]
                     (let [history (get m frame-id)
                           idx     (epoch-index history epoch-id)]
                       (if (nil? idx)
                         m
                         (let [record (nth history idx)
                               ;; Was there anything to splice? `:trace-events`
                               ;; is appended only when THIS record retained its
                               ;; raw stream (keep-window); a non-nil `row` rides
                               ;; the structured `slot`. No delta → pass-through
                               ;; (e.g. an unmount on a record whose trace stream
                               ;; was already dropped by the keep cap); the
                               ;; sensitivity rollup is NOT touched without a
                               ;; delta to carry it. `append?` is read off the
                               ;; RETRIED record so a CAS retry that re-resolves
                               ;; a different record re-decides correctly.
                               append? (contains? record :trace-events)
                               delta?  (or append? row?)]
                           (if-not delta?
                             m              ; no delta → pure pass-through
                             (assoc m frame-id
                                    (assoc history idx
                                           (cond-> record
                                             append? (splice-slot :trace-events event)
                                             row?    (splice-slot slot row)
                                             sens?   (assoc :rf.epoch/sensitive? true)))))))))
        ;; Read the spliced record back from the SAME post-swap value the
        ;; update committed — re-derive the index there (it may differ from any
        ;; pre-swap index if an append/eviction interleaved). nil when the
        ;; epoch was not (or no longer) in the ring.
        m'      (swap! histories splice-map)
        history (get m' frame-id)
        idx'    (epoch-index history epoch-id)]
    (when idx'
      (nth history idx'))))

(defn back-fill-render!
  "Back-fill a raw render event and its structured row into `:renders`."
  [frame-id epoch-id render-event render-row]
  (back-fill-event! frame-id epoch-id :renders render-event render-row))

(defn back-fill-sub-run!
  "Back-fill a raw subscription event and its structured row into `:sub-runs`."
  [frame-id epoch-id sub-event sub-run-row]
  (back-fill-event! frame-id epoch-id :sub-runs sub-event sub-run-row))

(defn back-fill-unmount!
  "Back-fill a raw unmount event. Unmounts have no structured row, so they
  affect only a retained `:trace-events` slot."
  [frame-id epoch-id unmount-event]
  (back-fill-event! frame-id epoch-id :renders unmount-event nil))

;; ---- per-cascade capture buffer -------------------------------------------
;;
;; Per Tool-Pair §Per-cascade capture: the drain runs traces through
;; `re-frame.trace/emit!` which fans out to every registered listener.
;; An internal listener appends every event into a per-cascade buffer;
;; when the cascade settles, the buffer is harvested and projected into
;; the structured record slots. Keyed by frame-id so concurrent drains
;; across frames don't co-mingle. Within a frame, drain-execution is
;; single-threaded (Spec 002 §Run-to-completion).

(defonce ^:private capture-buffers
  ;; frame-id → vector of trace events (in arrival order)
  (atom {}))

(defn buffer-event!
  "Append `event` onto the frame's in-flight cascade buffer.

  HOT PATH: fires once per `trace/emit!` while a cascade is in flight,
  which is the dominant per-event cost (sub-runs, renders, fx, error
  emits all funnel here). O(1) swap! + (fnil conj []) — the buffer
  vector grows by one and is harvested wholesale at cascade settle
  via `harvest-buffer!`."
  [frame-id event]
  (swap! capture-buffers update frame-id (fnil conj []) event))

(defn buffer-for
  "Return the frame's in-flight capture buffer (vector) or `[]`. Used
  by the destroy hook to inspect buffered events before deciding
  whether to commit a `:halted-destroy` partial record."
  [frame-id]
  (get @capture-buffers frame-id []))

(defn harvest-buffer!
  "Atomically read-and-clear the frame's in-flight buffer."
  [frame-id]
  (let [buffer (get @capture-buffers frame-id [])]
    (swap! capture-buffers dissoc frame-id)
    buffer))

(defn- run-start-dispatch-id
  "The `:dispatch-id` of the event being settled, read off the FIRST
  `:rf.event/run-start` emit in the buffer. nil when no run-start fired (a
  rejected / aborted dispatch, or a halt path whose event never ran) — the
  no-run-start branch of `harvest-buffer-for-event!` then falls back to the
  settling envelope's dispatch-id to scope its drop."
  [events]
  (some (fn [ev]
          (when (= :rf.event/run-start (:operation ev))
            (-> ev :tags :rf.trace/dispatch-id)))
        events))

;; A child's queue-time `:event/dispatched` trace is emitted while its parent
;; runs but carries the child's dispatch id. Keep it until the child's matching
;; run-start claims it, even when FIFO siblings settle first. Fixed-harvest
;; retention is incorrect because the child may be arbitrarily far behind its
;; parent. Terminal rejection, halt, and frame teardown clear stranded markers;
;; nil-id orphans are dropped because no event can ever claim them.

(defn harvest-buffer-for-event!
  "Atomically split the frame's buffer for one settling event.
  buffer and split it by the settling event's `:dispatch-id`:

    * RETURN the events that belong to the settling event — those whose
      `:tags :dispatch-id` matches the buffer's first `:event/run-start` id.
    * LEAVE in the buffer the events that belong to a DIFFERENT dequeued
      event — a child's `:event/dispatched` marker fires during the
      PARENT's do-fx (so it lands in the parent's window) but carries the
      CHILD's `:dispatch-id`; under per-event epochs it must ride the
      child's epoch, not the parent's. It stays buffered for the child's
      own `harvest-buffer-for-event!` at the child's settle.

  A marker is retained until its child's own run-start claims it, not for a fixed
  harvest count. A child that never runs is bounded by the terminal paths that
  clear the whole buffer (frame destroy / drain-interrupt / depth-halt /
  rejected dispatch); see the design note above this defn.

  With no run-start, no event ran to completion. The two-arity uses the
  settling envelope id to drop that rejected dispatch's own traces and nil-id
  orphans while retaining unrelated child markers. It returns `[]`, preventing
  `settle!` from recording a false `:ok` epoch. The low-level one-arity has no
  envelope id and therefore falls back to a full read-and-clear."
  ([frame-id] (harvest-buffer-for-event! frame-id nil))
  ([frame-id settling-dispatch-id]
  (let [buffer (get @capture-buffers frame-id [])]
    (if-let [settling-id (run-start-dispatch-id buffer)]
      ;; Return matching traces, retain non-nil ids for later events, and drop
      ;; nil-id orphans. The capture seam normally removes orphans first; this
      ;; partition is the defensive bound if one reaches storage.
      (let [mine   (filterv (fn [ev] (= (-> ev :tags :rf.trace/dispatch-id) settling-id))
                            buffer)
            ;; Other-event markers: a non-nil dispatch-id that isn't the
            ;; settling event's. Kept verbatim for the child's own settle.
            theirs (filterv (fn [ev]
                              (let [event-dispatch-id (-> ev :tags :rf.trace/dispatch-id)]
                                (and (some? event-dispatch-id)
                                     (not= event-dispatch-id settling-id))))
                            buffer)]
        ;; Leave the other-event markers in the buffer for their own event's
        ;; settle; drop orphans (nil dispatch-id) and the harvested-mine events.
        (if (seq theirs)
          (swap! capture-buffers assoc frame-id theirs)
          (swap! capture-buffers dissoc frame-id))
        mine)
      ;; No run-start means no event ran, so nothing can be committed.
      (if settling-dispatch-id
        ;; Scope the drop to THIS dispatch: drop its own traces (the rejection's
        ;; frame-stamped error trace, `:dispatch-id` = settling) + orphans, RETAIN
        ;; any unrelated sibling marker for its own settle, RETURN [] so `settle!`
        ;; commits no misleading `:ok` epoch. Symmetric with the run-start branch.
        (let [theirs (filterv (fn [ev]
                                (let [event-dispatch-id (-> ev :tags :rf.trace/dispatch-id)]
                                  (and (some? event-dispatch-id)
                                       (not= event-dispatch-id settling-dispatch-id))))
                              buffer)]
          (if (seq theirs)
            (swap! capture-buffers assoc frame-id theirs)
            (swap! capture-buffers dissoc frame-id))
          [])
        ;; 1-arity fallback (no envelope id — a direct low-level test call):
        ;; full read-and-clear, returning the whole buffer.
        (do (swap! capture-buffers dissoc frame-id)
            buffer))))))

(defn drop-frame-buffer!
  "Drop the frame's in-flight capture buffer."
  [frame-id]
  (swap! capture-buffers dissoc frame-id)
  nil)

(defn reset-capture-buffers!
  "Wipe every in-flight capture buffer across all frames. Test fixtures
  use this in lockstep with `reset-histories!` so stale traces cannot be
  harvested by the next test's first event."
  []
  (reset! capture-buffers {})
  nil)

(declare reset-frame-owners!)

(defn reset-histories!
  "Wipe every frame's recorded epochs. Also clears the last-settled-epoch
  and mount-attribution maps so a fixture's first cascade cannot back-fill
  a render into the previous fixture's record nor inherit a stale render-key → {mount-epoch +
  read-set} anchor."
  []
  (reset! histories {})
  (reset-last-settled-epochs!)
  (reset-mount-attribution!)
  (reset-frame-owners!)
  nil)

;; ---- listener registry ----------------------------------------------------

;; A listener registration is ONE atomic unit: the callback plus the
;; monotonically-increasing GENERATION token minted when it was installed.
;;
;;   listeners   cb-id → {:generation <token> :callback <fn>}
;;
;; Same-id replacement publishes a fresh generation (new token + callback) in a
;; SINGLE swap, so a concurrent fan-out that snapshots the new generation and
;; the registering thread can never tear the registration across two writes —
;; the split-brain the two-swap predecessor suffered (rf2-j538f7.5). The token
;; is drawn from a process-global monotonic counter so it is NEVER reused, not
;; even across a drop-then-re-register of the same id; that closes the ABA
;; window a per-id counter would leave (a recycled token could let a stale
;; fan-out arm a fresh registration).
(defonce ^:private listener-generation (atom 0))

(defn- next-listener-generation
  "Mint a fresh, process-unique listener generation token."
  []
  (swap! listener-generation inc))

(defonce ^:private listeners (atom {}))

;; Track which frames each callback GENERATION has observed. When a frame is
;; destroyed, every cb whose CURRENT generation observed that frame receives a
;; one-shot :rf.epoch.cb/silenced-on-frame-destroy trace. Each observation is
;; STAMPED with the generation token that recorded it:
;;
;;   observed-frames-by-cb   cb-id → {frame-id → generation-token}
;;
;; Stamping the observation with its generation is what makes same-id
;; replacement correct WITHOUT a cross-atom clear (the split-brain the two-swap
;; predecessor suffered): a replacement simply mints a new token, so a stale
;; OLD-generation observation is silently ignored by the token-scoped readers
;; (`record-observation!` refuses to re-arm it; `cbs-observing-frame` skips it)
;; and is overwritten the moment the NEW generation observes the same frame.
;; The frame-id stays the map KEY, so `(contains? (get observed cb-id)
;; frame-id)` still answers "did this cb ever observe the frame?" — the same
;; question a bare set answered, now carrying the owning generation alongside.
;; A same-keyed frame recreation re-arms because `drop-frame-observation!`
;; removes the frame on the prior destroy and the recreated frame's cascade
;; re-stamps it under the current generation.
(defonce ^:private observed-frames-by-cb
  ;; cb-id → {frame-id → generation-token}
  (atom {}))

(defn put-listener!
  "Install or replace `f` under `id` as a fresh listener GENERATION.

  The callback and its freshly-minted generation token are published in ONE
  atomic swap, so a same-id replacement can never be torn across two writes.
  The observation ledger is deliberately NOT cleared here: observations are
  stamped with the generation that recorded them (see `observed-frames-by-cb`),
  so a stale OLD-generation observation is ignored by the token-scoped readers
  and overwritten when the new generation re-observes the frame. This removes
  the two-swap window in which a concurrent fan-out's fresh observation of the
  new callback could be erased by a lagging second swap (rf2-j538f7.5), and its
  mirror in which a stale old callback could re-arm the new registration.

  Returns the id."
  [id f]
  (let [g (next-listener-generation)]
    (swap! listeners assoc id {:generation g :callback f}))
  id)

(defn drop-listener!
  "Remove the listener registered under `id` and any observation
  bookkeeping it carried."
  [id]
  (swap! listeners dissoc id)
  (swap! observed-frames-by-cb dissoc id)
  nil)

(defn reset-listeners!
  "Drop every registered listener and clear all observation bookkeeping."
  []
  (reset! listeners {})
  (reset! observed-frames-by-cb {})
  nil)

(defn listeners-snapshot
  "Return the current `{cb-id → {:generation g :callback f}}` registry map.
  Callers iterate this snapshot for fan-out — taking it once isolates
  iteration from concurrent `put-listener!` / `drop-listener!` updates, and
  each entry carries its generation token so the fan-out can stamp its
  observation against the exact generation it invoked."
  []
  @listeners)

(defn observations-snapshot
  "Return the current `{cb-id → {frame-id → generation-token}}` map."
  []
  @observed-frames-by-cb)

(defn record-observation!
  "Mark that the cb GENERATION `token` registered under `cb-id` has seen a
  record from `frame-id`. `notify-listeners!` snapshots `[cb-id token callback]`
  and calls this before invoking `callback`, so the observation is attributed to
  the exact generation that consumed the record.

  Two hazards this closes, both scoped by `token`:

    * The recorded generation is no longer current — a same-id replacement
      minted a new token, or `unregister-epoch-listener!` dropped the id
      entirely. Re-adding the observation would either re-arm a retired
      generation or, worse, arm the NEW generation for a frame it never
      consumed. The swap therefore lands ONLY when `token` still equals the
      live generation for `cb-id` (a dropped id has no entry, so no token
      matches — this subsumes the rf2-7i872 unregister-liveness guard).

    * A no-op re-observation of the same (cb, generation, frame) triple — the
      steady-state hot path — must not fire the atom watcher. The outer guard
      short-circuits before any swap when the frame is already stamped with
      this token: one deref + lookup, no swap.

  Both checks ride INSIDE the swap as well, so the decision is taken against a
  consistent listener snapshot on every CAS retry — a replace/drop landing
  mid-swap is honoured."
  [cb-id token frame-id]
  (when frame-id
    (let [observed @observed-frames-by-cb]
      (when (and (not= token (get-in observed [cb-id frame-id]))
                 (= token (:generation (get @listeners cb-id))))
        (swap! observed-frames-by-cb
               (fn [m]
                 (if (or (= token (get-in m [cb-id frame-id]))
                         ;; Generation re-check inside the swap: a same-id
                         ;; replacement or `drop-listener!` that landed between
                         ;; the outer guard and this CAS attempt must not be
                         ;; undone by arming a retired/replaced generation.
                         (not= token (:generation (get @listeners cb-id))))
                   m
                   (assoc-in m [cb-id frame-id] token))))))))

(defn cbs-observing-frame
  "Return the cb-ids whose CURRENT generation observed `frame-id` — the frame
  is stamped in the cb's observation ledger with the cb's live generation
  token. A stale OLD-generation stamp (left by a since-replaced generation that
  never re-observed the frame) is NOT returned, so a same-id replacement never
  silences the new callback for a frame only the OLD callback consumed, and
  never fails to silence a frame the new callback did consume. The one-shot
  `:rf.epoch.cb/silenced-on-frame-destroy` fan is derived from this set."
  [frame-id]
  (let [observed @observed-frames-by-cb
        live     @listeners]
    (->> observed
         (keep (fn [[cb-id frames]]
                 (let [token (get frames frame-id ::absent)]
                   (when (and (not= token ::absent)
                              (= token (:generation (get live cb-id))))
                     cb-id))))
         vec)))

(defn drop-frame-observation!
  "Drop `frame-id` from every cb's observation ledger. cbs whose ledger goes
  empty as a result are dropped from the map entirely so the map doesn't
  accrete keys to empty ledgers."
  [frame-id]
  (swap! observed-frames-by-cb
         (fn [m]
           (reduce-kv (fn [acc cb-id frames]
                        (let [frames' (dissoc frames frame-id)]
                          (if (empty? frames')
                            (dissoc acc cb-id)
                            (assoc acc cb-id frames'))))
                      {}
                      m)))
  nil)

;; ---- frame-incarnation ownership -----------------------------------------
;;
;; Epoch stores are addressed publicly by frame id, but teardown may overlap a
;; fresh same-id incarnation: destroy(A) dissocs A before its final epoch hook,
;; allowing B to publish epoch state before A reaches that hook. This private
;; token ledger serialises owner replacement against final cleanup so A can
;; never delete B's id-keyed history/buffer/attribution/observations.

(defonce ^:private frame-owner-tokens (atom {}))

#?(:clj
   (defonce ^:private frame-owner-lock (Object.)))

(defn- with-frame-owner-lock [f]
  #?(:clj  (locking frame-owner-lock (f))
     :cljs (f)))

(defn- drop-frame-epoch-state!
  "Drop every epoch side-table entry owned under `frame-id`.

  Caller holds the frame-owner serialization. Listener registrations are
  process-wide and survive; only this frame's observation stamps are removed."
  [frame-id]
  (drop-frame-observation! frame-id)
  (drop-frame-history! frame-id)
  (drop-frame-buffer! frame-id)
  (drop-last-settled-epoch! frame-id)
  (drop-frame-mount-attribution! frame-id))

(defn claim-frame-owner!
  "Claim the id-keyed epoch stores for one exact live frame incarnation.

  `owner-token` is the frame's stable incarnation token. Re-claim by the same
  token is a no-op. A fresh token first clears any stale predecessor state,
  then publishes itself under the same serialization final teardown uses.
  Returns true when a non-nil token owns the stores, false otherwise."
  [frame-id owner-token]
  (boolean
    (when (and frame-id owner-token)
      ;; The common path is a re-claim by the current incarnation. Avoid the
      ;; global debug-only serialization on every captured trace; the locked
      ;; path rechecks before replacing so this fast read cannot weaken the
      ;; same-id hand-off.
      (if (identical? (get @frame-owner-tokens frame-id) owner-token)
        true
        (with-frame-owner-lock
          (fn []
            (let [current (get @frame-owner-tokens frame-id)]
              (when-not (identical? current owner-token)
                (drop-frame-epoch-state! frame-id)
                (swap! frame-owner-tokens assoc frame-id owner-token)))
            true))))))

(defn cleanup-frame-owner!
  "Run `cleanup-fn` only when `owner-token` still owns `frame-id`'s epoch
  stores (or no epoch state was ever claimed for the dying incarnation).

  Owner comparison and the complete cleanup run share one serialization with
  `claim-frame-owner!`. Thus either stale A cleanup wins before B publishes any
  state, or B claims first and A becomes a no-op; A can never erase B after the
  comparison. Returns `cleanup-fn`'s value when cleanup ran, nil for a stale
  owner. Cleanup functions therefore return a non-nil result when the caller
  needs to distinguish a successful empty cleanup from a stale owner."
  [frame-id owner-token cleanup-fn]
  (with-frame-owner-lock
    (fn []
      (let [current (get @frame-owner-tokens frame-id)]
        (when (or (nil? current) (identical? current owner-token))
          (try
            (cleanup-fn)
            (finally
              ;; Compare-remove: retain an unexpected re-entrant replacement.
              (swap! frame-owner-tokens
                     (fn [owners]
                       (let [latest (get owners frame-id)]
                         (if (or (nil? latest)
                                 (identical? latest owner-token))
                           (dissoc owners frame-id)
                           owners)))))))))))

(defn reset-frame-owners!
  "Clear the private incarnation ledger for fixture/global history reset."
  []
  (reset! frame-owner-tokens {})
  nil)
