(ns re-frame.epoch.state
  "Private storage and low-level state operations for epoch history.

  State is split by access pattern:

    config                  per-frame ring-buffer config
    epoch-counter           monotonically-increasing :epoch-id source
    histories               frame-id → vector<:rf/epoch-record>
    last-settled-epoch      frame-id → epoch-id of the most-recently-
                            settled epoch (async back-fill anchor)
    mount-attribution       frame-id → {render-key → {:epoch-id E
                                                       :deps #{sub-id…}}}
                            (mount anchor and learned view read-set)
    capture-buffers         frame-id → vector<trace-event> (in-flight)
    listeners               cb-id    → callback fn

  Capture buffers stay separate from rarely-written back-fill state because
  every trace emit updates them."
  (:require [re-frame.privacy :as rf.privacy]))

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
  {:depth             default-depth
   :trace-events-keep default-trace-events-keep})

(defonce ^:private config
  (atom default-config))

(defn non-neg-int?
  "True for non-negative integer values; nil and non-numeric values
  fail. Mirrors the validation `re-frame.trace.tooling/configure-trace-buffer!`
  applies at its own config boundary."
  [x]
  (and (integer? x) (not (neg? x))))

;; ---- retention serialization ----------------------------------------------
;;
;; `:depth` is a RULE about two stores — the per-frame rings, and the
;; last-settled anchors that name records inside them — and both the rule and
;; the stores are written in more than one step:
;;
;;   record!                  reads the depth, THEN swaps `histories`
;;   set-last-settled-epoch!  reads the depth, THEN swaps `last-settled-epoch`
;;   merge-config!            swaps `config`, THEN prunes every ring, THEN
;;                            reconciles the anchors against the pruned map
;;
;; Left unserialized those sequences interleave, and the interleavings are not
;; benign. A writer that captured the PREVIOUS depth can commit its append
;; after `configure!` has already returned, which republishes the exact defect
;; the boundary prune exists to close: the excess record is queryable through
;; `epoch-history`, and it is a live `restore-epoch!` /
;; `replay-epoch!` target. Swapping the config before the prune makes that
;; escape self-repairing for a POSITIVE depth — the next append re-caps the
;; ring — but PERMANENT at depth 0, where `record!` never appends again. And
;; "a later append repairs it" is not the promise: the promise is about the
;; state at `configure!`'s RETURN. Symmetrically, an anchor published at the
;; seam between the prune and the reconciliation is judged against a snapshot
;; taken before its record existed, so a CORRECT anchor is discarded as
;; unretained.
;;
;; One monitor closes both: it is held across each whole sequence, so
;; `configure!` cannot return while a writer still holds a stale depth, and no
;; anchor decision is taken against a stale ring.
;;
;; DEADLOCK-FREE BY CONSTRUCTION. This is a LEAF: every section it guards is
;; atom swaps over the epoch stores and nothing else — no other lock is acquired
;; underneath it and no foreign code runs there. A lock that acquires nothing
;; cannot supply the second edge
;; of a cycle. `commit-frame-owner-record!` reaches it from INSIDE the
;; frame-owner serialization, which fixes the only nesting that exists as
;; frame-owner → retention; the JVM monitor is reentrant, so the two writers
;; it calls take it without re-blocking.
;;
;; CLJS is single-threaded and keeps the simple path: the helper is a bare call.

#?(:clj
   (defonce ^:private retention-lock (Object.)))

(defn- with-retention-lock [f]
  #?(:clj  (locking retention-lock (f))
     :cljs (f)))

(declare enforce-depth! enforce-trace-events-keep!)

(defn merge-config!
  "Validate and merge an `opts` map into the live config atom. Returns
  nil. Silently drops invalid slot values (`:depth` /
  `:trace-events-keep` must be non-negative integers; anything else is
  dropped).

  Validation at this boundary keeps numeric assumptions out of the hot path.

  Both accepted knobs are enforced against the rings that already exist, not
  only against future appends — see `enforce-depth!` and
  `enforce-trace-events-keep!`.

  The config swap and that enforcement are ONE step under the retention lock,
  so no writer holding the previous depth can commit between them — see the
  retention-serialization section above."
  [opts]
  (when (map? opts)
    (let [numeric-options (select-keys opts [:depth :trace-events-keep])
          valid-options (into {}
                              (filter (fn [[_ option-value]]
                                        (non-neg-int? option-value)))
                              numeric-options)]
      (when (seq valid-options)
        (with-retention-lock
          (fn []
            (swap! config merge valid-options)
            ;; Config first, then the rings — but the ordering is not
            ;; what carries the invariant. Both are inside the retention
            ;; lock, which is what makes the prune un-undoable: a `record!`
            ;; that read the previous depth cannot be mid-flight here, and
            ;; one that starts after this section reads the new depth.
            (when (contains? valid-options :depth)
              (enforce-depth! (:depth valid-options)))
            (when (contains? valid-options :trace-events-keep)
              (enforce-trace-events-keep! (:trace-events-keep valid-options))))))))
  nil)

(defn current-config
  "Return the current epoch-history configuration map."
  []
  @config)

(defn reset-config!
  "Restore the live config atom to the shipped `default-config`
  baseline (`:depth` 50, `:trace-events-keep` 50). Replacing the whole
  map ensures test fixtures start from the shipped values. Returns nil."
  []
  (reset! config default-config)
  nil)

(defn depth []
  (:depth @config default-depth))

(defn trace-events-keep []
  (:trace-events-keep @config default-trace-events-keep))

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
  "When the record at index `(- (count history) trace-events-keep 1)` crosses
  the keep-boundary, dissoc its `:trace-events`. O(1) per append: every
  earlier record was already elided on its own crossing, so the only
  record that needs work is the one that just slid out of the keep-
  window. Records keep their structured projections (`:sub-runs` /
  `:renders` / `:effects`) but lose the raw trace stream. nil
  `trace-events-keep` means 'keep every record's :trace-events'.

  Invoked from `append-record` for every event epoch. Under steady state only
  one record per append
  actually transitions out of the keep-window, so touching just that
  record (rather than walking the whole history vector) is all that is
  needed. The steady-state invariant holds because every prior append
  already elided its own just-crossed record, and a runtime reduction of
  `trace-events-keep` re-establishes it on the live rings before
  `configure!` returns (`enforce-trace-events-keep!`)."
  [history trace-events-keep]
  (let [history-count (count history)]
    (if (and (some? trace-events-keep)
             (nat-int? trace-events-keep)
             (> history-count trace-events-keep))
      (let [crossed-index (- history-count trace-events-keep 1)
            record        (nth history crossed-index)]
        (if (contains? record :trace-events)
          (assoc history crossed-index (dissoc record :trace-events))
          history))
      history)))

(defn- append-record
  "Conj `record` onto the frame's history vector, cap to `depth` by
  MATERIALISING the most-recent-`depth` window into a fresh vector, then
  elide the just-crossed record's `:trace-events` per `trace-events-keep`.

  The cap MUST materialise — a bare `(subvec appended-history ...)` view does
  NOT release the evicted records. `SubVector.cons` keeps appending to
  the SAME growing underlying vector and `subvec` of a `SubVector`
  re-wraps that same backing vector, so over a long session the
  retained-window view's backing PersistentVector accretes every record ever
  appended (each carrying its full `:db-before` / `:db-after` /
  `:trace-events` payload) — an unbounded heap leak that defeats the
  bounded-ring contract even though `history-for` correctly returns
  only `depth` records. `(into [] (subvec ...))` copies the `depth`-element
  window into a concrete PersistentVector whose backing is exactly `depth`,
  so the evicted records become GC-eligible.

  HOT PATH: fires once per cascade settle, i.e. once per dispatched
  user event under steady state. Below the cap the append is O(1) (a
  plain `conj`); once the ring is full each append is O(depth) — a `depth`-wide
  copy of the retained window (depth defaults to 50, fired once per
  user-facing event, not per trace emit). The bounded copy is the
  necessary cost of bounded heap; a bare `subvec` view would be O(1)
  in time but O(session-length) in retained heap. The trace-events
  elision stays O(1) — at most one record's `:trace-events` slot is
  dissoc'd."
  [history record depth trace-events-keep]
  (let [appended-history (conj (or history []) record)
        history-count    (count appended-history)
        capped-history   (if (and (pos? depth) (> history-count depth))
                           (into [] (subvec appended-history
                                           (- history-count depth)))
                           appended-history)]
    (elide-just-crossed-trace-events capped-history trace-events-keep)))

(defn record!
  "Append a record into the frame's history. The depth cap and the
  `:trace-events-keep` cap are read from the config atom on each
  append so runtime `(rf/configure! {:epoch-history ...})` takes effect
  immediately.

  The depth READ and the ring write are one step under the retention lock.
  Split, they are a hole in `configure!`'s post-return invariant: an append
  that captured the previous depth commits after the boundary prune has run,
  and at depth 0 nothing later re-caps the ring (this function appends
  nothing at depth 0, so the escaped record is permanent). See the
  retention-serialization section at the top of this namespace."
  [record]
  (with-retention-lock
    (fn []
      (let [history-depth        (depth)
            trace-events-to-keep (trace-events-keep)]
        (when (pos? history-depth)
          (let [frame-id (:frame record)]
            (swap! histories update frame-id append-record record
                   history-depth trace-events-to-keep)))))))

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
  (some (fn [history-index]
          (when (= epoch-id (:epoch-id (nth history history-index)))
            history-index))
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
  no back-fill — correct under depth 0.

  That gate is a depth read followed by a store write, which is the shape
  `record!` has, so it takes the same retention lock for the same reason: an
  anchor must not be published against a depth `configure!` has already
  replaced, and `enforce-depth!`'s reconciliation must not be judging an
  anchor against a ring snapshot taken before it existed."
  [frame-id epoch-id]
  (with-retention-lock
    (fn []
      (when (and frame-id epoch-id (pos? (depth)))
        (swap! last-settled-epoch assoc frame-id epoch-id))))
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
           (fn [attribution-map]
             (if (get-in attribution-map [frame-id render-key :epoch-id])
               attribution-map
               (assoc-in attribution-map
                         [frame-id render-key :epoch-id]
                         epoch-id)))))
  nil)

(defn drop-render-key-mount-attribution!
  "Forget the mount-anchor + read-set for a SINGLE `render-key` in
  `frame-id` — the per-instance eviction tied to a view
  instance's UNMOUNT. Without it `mount-attribution` would accrete one
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
           (fn [attribution-map]
             (let [pruned-attribution-map
                   (update attribution-map frame-id dissoc render-key)]
               (if (empty? (get pruned-attribution-map frame-id))
                 (dissoc pruned-attribution-map frame-id)
                 pruned-attribution-map)))))
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

(defn- view-value-changed-sub-runs
  "The value-changed `:sub/run` evidence in epoch `record` belonging to the
  view named by `render-key`, as a seq of raw trace events or structured rows.

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
       keep-0 would see NO value-change evidence and mis-record genuine
       re-renders against the mount/default epoch.

  `render-deps` may be nil when the view's read-set was never learned — then only the
  render-key (trace) match applies, and the structured fallback yields nothing.
  Prefers the trace source when present so the render-key precision is kept;
  falls back to the structured rows only when traces are absent."
  [record render-key render-deps]
  (if (contains? record :trace-events)
    (filter (fn [trace-event]
              (and (= :rf.sub/run (:operation trace-event))
                   (true? (-> trace-event :tags :rf.sub/value-changed?))
                   (let [event-tags (:tags trace-event)]
                     (or (= render-key (:rf.sub/reader-render-key event-tags))
                         (and render-deps
                              (contains? render-deps (:rf.sub/id event-tags)))))))
            (:trace-events record))
    ;; `:trace-events` elided (keep-0, or this record below the
    ;; elision boundary). The structured `:sub-runs` rows are retained for
    ;; every record; match a value-changed row by the view's learned read-set.
    (when render-deps
      (filter (fn [sub-run-row]
                (and (true? (:value-changed? sub-run-row))
                     (contains? render-deps (:sub-id sub-run-row))))
              (:sub-runs record)))))

(defn- epoch-value-changed-for-view?
  "True when epoch `record` carries a value-changed `:sub/run` belonging
  to the view named by `render-key` (see `view-value-changed-sub-runs`)."
  [record render-key render-deps]
  (boolean (seq (view-value-changed-sub-runs record render-key render-deps))))

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
  truncating the ring, so the newer pre-restore epochs remain —
  and they still carry value-changed sub-run evidence for a repainted view. An
  UNBOUNDED newest-first scan would return one of those STALE newer epochs,
  attributing a POST-restore repaint to a PRE-restore cascade and silently
  defeating the restore re-anchor (the render-path sibling of the restore's
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
  (let [history       (history-for frame-id)
        render-deps   (render-deps-for frame-id render-key)
        history-count (count history)
        ;; Records newer than the anchor exist only after a restore rewind;
        ;; start the newest-first scan at the anchor so they are excluded.
        start-index (or (epoch-index history anchor-epoch-id)
                        (dec history-count))]
    (loop [history-index start-index]
      (when (>= history-index 0)
        (let [record (nth history history-index)]
          (if (epoch-value-changed-for-view? record render-key render-deps)
            (:epoch-id record)
            (recur (dec history-index))))))))

(defn resolve-render-epoch
  "Resolve the epoch a post-settle render of `render-key` should be
  attributed to in `frame-id`. Falls back to `default-epoch-id`, the most
  recently settled epoch, when no better anchor is found.

  Resolution order:
    1. The newest epoch AT OR BEFORE the anchor in which the view's OWN
       inputs changed (`value-changed-epoch-for`, bounded at
       `default-epoch-id`) — a genuine reactive re-render rides its causing
       cascade. The anchor bound keeps a post-restore repaint on the restored
       timeline rather than attributing it to a stale newer pre-restore
       epoch.
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
        record-index (epoch-index history epoch-id)]
    (boolean
      (when record-index
        (some (fn [render-row]
                (= render-key (:render-key render-row)))
              (:renders (nth history record-index)))))))

;; ---- post-settle epoch windows --------------------------------------------
;;
;; A post-settle recompute is filed under the last-settled epoch, but it runs
;; against the db as it stands after EVERY epoch that settled since the sub
;; last ran, and any of them may have changed its inputs. Which one did is
;; decided inside the sub body, and the epoch layer never runs user code to
;; find out. So the filed run carries the window of epochs it reflects, and a
;; render attributed through it carries the same window.

(defn sub-run-epoch-window
  "Return `[first last]`: the ids of the oldest and newest settled epochs that
  a post-settle recompute of `query-v`, filed under `anchor-epoch-id`, may
  reflect.

  `last` is the anchor. `first` comes from a newest-first scan of the frame's
  ring, starting at the anchor, back to the sub's previous recorded run — the
  newest `:sub-runs` row carrying `query-v`. That run's own epoch is in the
  window when the run was recorded inside its cascade, because the cascade
  may have changed the sub's inputs after it ran, and out of it when the run
  was itself post-settle (it carries a window). With no previous run retained
  the window reaches back to the oldest retained settled epoch; with no epoch
  settled since the previous run it is the anchor alone.

  Only `:ok` records join, since a halted record never settled. Starting at
  the anchor, as `value-changed-epoch-for` does, keeps records newer than a
  restore rewind out of the window."
  [frame-id anchor-epoch-id query-v]
  (let [history (history-for frame-id)]
    (loop [history-index  (or (epoch-index history anchor-epoch-id) -1)
           first-epoch-id anchor-epoch-id]
      (if (neg? history-index)
        [first-epoch-id anchor-epoch-id]
        (let [record       (nth history history-index)
              settled?     (= :ok (get record :outcome :ok))
              previous-run (last (filter #(= query-v (:query-v %))
                                         (:sub-runs record)))]
          (cond
            (nil? previous-run)
            (recur (dec history-index)
                   (if settled? (:epoch-id record) first-epoch-id))

            (and settled? (not (contains? previous-run :epoch-window)))
            [(:epoch-id record) anchor-epoch-id]

            :else
            [first-epoch-id anchor-epoch-id]))))))

(defn- epoch-window-of
  "The epoch window on a sub-run, read off a structured row or a raw trace."
  [sub-run]
  (or (:epoch-window sub-run)
      (-> sub-run :tags :rf.sub/epoch-window)))

(defn render-epoch-window
  "The epoch window a post-settle render filed under `epoch-id` inherits from
  the value-changed sub-runs that attributed it there (see
  `resolve-render-epoch`): the widest `[first last]` across the view's
  windowed evidence in that epoch, or nil when none of it carries a window.
  Epoch ids are opaque, so \"widest\" is by ring position."
  [frame-id render-key epoch-id]
  (when render-key
    (let [history      (history-for frame-id)
          record-index (epoch-index history epoch-id)
          windows      (when record-index
                         (keep epoch-window-of
                               (view-value-changed-sub-runs
                                 (nth history record-index) render-key
                                 (render-deps-for frame-id render-key))))
          ;; An evicted epoch is older than every retained one.
          position     #(or (epoch-index history %) -1)]
      (when (seq windows)
        [(apply min-key position (map first windows))
         (apply max-key position (map second windows))]))))

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
  the target was evicted or never stored, or when nothing was appended (the
  record kept no raw stream and there is no structured row) — so a listener is
  never re-sent a record that did not change.

  Raw trace is appended only when that record retained `:trace-events`; a
  non-nil structured row is appended independently to `projection-slot`.
  Sensitive evidence is ORed into the record-level rollup. Projection never
  runs on this storage path.

  Back-fill runs outside the frame drain and can race ring append/eviction.
  Therefore the epoch index must be resolved inside the CAS-retried `swap!`
  update from the same history map being rewritten. Resolving it earlier could
  splice the wrong record after a capped-ring eviction shifts indices. The
  update function remains pure so retries are safe."
  [frame-id epoch-id projection-slot trace-event structured-row]
  (let [;; The record-level rollup must include post-settle trace evidence,
        ;; but `build-record` computes it ONCE at settle time over the
        ;; SETTLE-TIME events; a post-settle back-fill of a `:sensitive?`-
        ;; stamped trace event would otherwise leave the rollup stale-false.
        ;; `privacy/sensitive?` is pure and depends only on `trace-event` (not
        ;; on any @histories snapshot), so it is hoisted out of the swap; the
        ;; swap fn ORs it into the rollup fail closed. The OR is
        ;; monotonic/idempotent (only flips false→true, never clears a
        ;; settle-time true), so it rides safely inside the CAS-retried swap.
        trace-sensitive? (rf.privacy/sensitive? trace-event)
        structured-row?  (some? structured-row)
        append-slot-value (fn [record target-slot slot-value]
                            (cond
                              (vector? (get record target-slot))
                              (update record target-slot conj slot-value)

                              (not (contains? record target-slot))
                              (assoc record target-slot [slot-value])

                              :else        ; target slot already a scalar
                              record))     ; delta subsumed
        ;; Operate on the whole histories map so the index is
        ;; re-derived from the SAME (CAS-retried) value the splice rewrites —
        ;; never against a separate, possibly-shifted deref. When the epoch is
        ;; no longer in the frame's ring (evicted, or never landed) the map is
        ;; returned unchanged and the post-swap read-back yields nil.
        update-histories (fn [histories-map]
                           (let [history      (get histories-map frame-id)
                                 record-index (epoch-index history epoch-id)]
                             (if (nil? record-index)
                               histories-map
                               (let [record (nth history record-index)
                                     ;; Was there anything to splice?
                                     ;; `:trace-events` is appended only when
                                     ;; THIS record retained its raw stream
                                     ;; (keep-window); a structured row rides
                                     ;; `projection-slot`. No delta means pure
                                     ;; pass-through. `append-trace?` is read
                                     ;; off the RETRIED record so a CAS retry
                                     ;; re-decides against the record it found.
                                     append-trace? (contains? record :trace-events)
                                     has-delta?    (or append-trace?
                                                       structured-row?)]
                                 (if-not has-delta?
                                   histories-map
                                   (assoc histories-map frame-id
                                          (assoc history record-index
                                                 (cond-> record
                                                   append-trace?
                                                   (append-slot-value
                                                     :trace-events trace-event)
                                                   structured-row?
                                                   (append-slot-value
                                                     projection-slot structured-row)
                                                   trace-sensitive?
                                                   (assoc :rf.epoch/sensitive?
                                                          true)))))))))
        ;; Read the spliced record back from the SAME post-swap value the
        ;; update committed — re-derive the index there (it may differ from any
        ;; pre-swap index if an append/eviction interleaved). An unchanged map
        ;; means nothing was spliced, so there is nothing to re-notify.
        [histories-before
         histories-after]    (swap-vals! histories update-histories)
        updated-history      (get histories-after frame-id)
        updated-record-index (epoch-index updated-history epoch-id)]
    (when (and updated-record-index
               (not (identical? histories-before histories-after)))
      (nth updated-history updated-record-index))))

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
  (get (first (swap-vals! capture-buffers dissoc frame-id)) frame-id []))

(defn- run-start-dispatch-id
  "The `:dispatch-id` of the buffer's FIRST `:rf.event/run-start` emit, or nil
  when no run-start fired. It names the settling event only for a caller that
  has no settling dispatch id of its own."
  [trace-events]
  (some (fn [trace-event]
          (when (= :rf.event/run-start (:operation trace-event))
            (-> trace-event :tags :rf.trace/dispatch-id)))
        trace-events))

(defn- split-for-event
  "Split `buffered-events` for one settling event, as `[settling remaining]`.

  The settling event is `settling-dispatch-id`, else the buffer's first
  run-start. Keying on the caller's id is what keeps attribution exact: a
  run-start a thrown event left behind can never claim the next event's
  settle. `settling` is that event's traces when its run-start is buffered,
  else `[]` — nothing ran, so nothing commits. `remaining` keeps every trace
  carrying a DIFFERENT non-nil dispatch id and drops nil-id orphans. With no
  id from either source the whole buffer settles and nothing remains. Pure, so
  it can run inside a CAS-retried swap."
  [buffered-events settling-dispatch-id]
  (if-let [event-dispatch-id (or settling-dispatch-id
                                 (run-start-dispatch-id buffered-events))]
    (let [own? #(= event-dispatch-id (-> % :tags :rf.trace/dispatch-id))]
      [(if (some #(and (own? %) (= :rf.event/run-start (:operation %)))
                 buffered-events)
         (filterv own? buffered-events)
         [])
       (filterv #(let [dispatch-id (-> % :tags :rf.trace/dispatch-id)]
                   (and (some? dispatch-id) (not= dispatch-id event-dispatch-id)))
                buffered-events)])
    [buffered-events []]))

;; A child's queue-time `:event/dispatched` trace is emitted while its parent
;; runs but carries the child's dispatch id. Keep it until the child's matching
;; run-start claims it, even when FIFO siblings settle first. Fixed-harvest
;; retention is incorrect because the child may be arbitrarily far behind its
;; parent. Terminal rejection, halt, and frame teardown clear stranded markers;
;; nil-id orphans are dropped because no event can ever claim them.

(defn harvest-buffer-for-event!
  "Atomically split the frame's buffer for one settling event (`split-for-event`):

    * RETURN the settling event's own traces — or `[]` when its run-start is
      not buffered, because no event ran to completion and `settle!` must not
      record a false `:ok` epoch (a rejected dispatch's own error trace is
      dropped, not returned).
    * LEAVE in the buffer the events that belong to a DIFFERENT dequeued
      event — a child's `:event/dispatched` marker fires during the
      PARENT's do-fx (so it lands in the parent's window) but carries the
      CHILD's `:dispatch-id`; under per-event epochs it must ride the
      child's epoch, not the parent's. It stays buffered for the child's
      own `harvest-buffer-for-event!` at the child's settle.
    * DROP nil-id orphans. The capture seam normally removes them first; this
      partition is the defensive bound if one reaches storage.

  A marker is retained until its child's own run-start claims it, not for a fixed
  harvest count. A child that never runs is bounded by the terminal paths that
  clear the whole buffer (frame destroy / drain-interrupt / depth-halt /
  rejected dispatch); see the design note above this defn. A nil
  `settling-dispatch-id` keys on the buffer's first run-start, and with none
  falls back to a full read-and-clear.

  One `swap-vals!`, so a trace another thread buffers mid-harvest — a
  cross-thread dispatch's `:rf.event/dispatched` marker — is never overwritten."
  [frame-id settling-dispatch-id]
  (let [[before _] (swap-vals! capture-buffers
                               (fn [buffers]
                                 (let [[_ remaining] (split-for-event
                                                       (get buffers frame-id [])
                                                       settling-dispatch-id)]
                                   (if (seq remaining)
                                     (assoc buffers frame-id remaining)
                                     (dissoc buffers frame-id)))))]
    (first (split-for-event (get before frame-id []) settling-dispatch-id))))

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

;; ---- depth-change enforcement ---------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history" the runtime keeps
;; the last N epochs per frame and older epochs are discarded, and the
;; depth-0 bullet is explicit that disabling the ring makes `epoch-history`
;; return `[]`. Both are statements about the CURRENT depth, so a reduction
;; has to bite when it is accepted rather than on some later append.
;;
;; Enforcing only at append time would leave the excess reachable by two
;; different routes, and closing one without the other would still be a
;; defect. It would stay QUERYABLE — `epoch-history` reads the ring vector
;; directly — and RESTORABLE, because `restore-epoch!`
;; and `replay-epoch!` resolve their targets off that same vector, so an id
;; the operator believes gone would still rewind the frame to state the app
;; has moved past. At depth 0 that would be permanent as well: `record!` skips
;; `append-record` entirely at depth 0, so no later append ever arrives to
;; repair the ring.

(defn- cap-to-depth
  "Trim `history` to its newest `depth` records, oldest-first; `depth` 0
  drops them all.

  Materialises the retained window for the same reason `append-record`
  does — a bare `subvec` view keeps the evicted records alive through the
  shared backing vector, which would make this a no-op in precisely the
  case the caller lowered the depth to reclaim heap.

  Pruning cannot disturb the `:trace-events-keep` invariant it walks past:
  elision runs oldest-first and is one-way, so the records still holding
  raw traces are the newest ones, and keeping a SUFFIX only moves them
  closer to the end of the vector."
  [history depth]
  (let [history-count (count history)]
    (cond
      (zero? depth)            []
      (<= history-count depth) history
      :else                    (into [] (subvec history (- history-count depth))))))

(defn- enforce-depth!
  "Cap every frame's ring to `depth` and reconcile the back-fill anchors
  naming records the cap dropped. Called from `merge-config!` whenever a
  valid `:depth` is accepted, which is what makes the caller-visible
  invariant true: once `configure!` returns, every ring respects the
  accepted depth.

  Runs on any accepted `:depth` rather than on a detected REDUCTION. A
  raise is already a no-op here — every ring is at most the previous,
  smaller cap — and one path is cheaper to be sure of than two.

  ORDER: rings first, anchors second, and the anchors are reconciled
  against the PRUNED map. An anchor left naming a dropped record is not
  merely untidy: `resolve-render-epoch` hands it to the post-settle splice,
  whose `epoch-index` then resolves nil, so the render is silently DROPPED
  instead of being attributed to the live cascade. `last-settled-epoch` is
  the whole-frame anchor; `mount-attribution`'s `:epoch-id` is the
  per-render-key one. Only the `:epoch-id` slot goes — a render-key's
  learned `:deps` read-set is not an epoch reference, and it is learned at
  MOUNT, so discarding it here could never be re-learned for a view that is
  already mounted. `record-mount-epoch!` re-mints the dropped anchor on the
  view's next attribution, the same way it does after
  `drop-render-key-mount-attribution!`. `reset-histories!` below clears
  these same two anchors in lockstep, for the same reason.

  Against a concurrent JVM append: the CALLER holds the retention lock across
  its config swap and this call, and `record!` / `set-last-settled-epoch!`
  take that same lock across their own depth-read-then-write. So there is no
  in-flight writer holding the previous depth to land after this prune, and
  the retained-id set below cannot be stale with respect to a concurrently
  committed record. Without the lock the excess would be transient for a
  positive depth but NOT at depth 0 — `record!` appends nothing there, so no
  later append would ever re-cap the ring. Returns nil."
  [depth]
  (let [pruned-histories (swap! histories
                                (fn [histories-map]
                                  (reduce-kv (fn [acc frame-id history]
                                               (assoc acc frame-id
                                                      (cap-to-depth history depth)))
                                             {}
                                             histories-map)))
        retained-ids     (into {}
                               (map (fn [[frame-id history]]
                                      [frame-id (into #{} (map :epoch-id) history)]))
                               pruned-histories)
        retained?        (fn [frame-id epoch-id]
                           (contains? (get retained-ids frame-id) epoch-id))]
    (swap! last-settled-epoch
           (fn [anchors]
             (reduce-kv (fn [acc frame-id epoch-id]
                          (cond-> acc
                            (retained? frame-id epoch-id) (assoc frame-id epoch-id)))
                        {}
                        anchors)))
    (swap! mount-attribution
           (fn [attribution-map]
             (reduce-kv
               (fn [acc frame-id render-key->entry]
                 (let [kept (reduce-kv
                              (fn [frame-acc render-key entry]
                                (let [entry (cond-> entry
                                              (not (retained? frame-id (:epoch-id entry)))
                                              (dissoc :epoch-id))]
                                  ;; Drop the entry — and then the frame's
                                  ;; whole map — when nothing is left, so a
                                  ;; prune leaves no residual `{frame {}}`
                                  ;; shell (as `drop-render-key-mount-
                                  ;; attribution!` does).
                                  (cond-> frame-acc
                                    (seq entry) (assoc render-key entry))))
                              {}
                              render-key->entry)]
                   (cond-> acc
                     (seq kept) (assoc frame-id kept))))
               {}
               attribution-map))))
  nil)

(defn- enforce-trace-events-keep!
  "Drop `:trace-events` from every retained record outside its ring's newest
  `trace-events-keep`. Called from `merge-config!` whenever a valid
  `:trace-events-keep` is accepted, under the same retention lock as
  `enforce-depth!`, so once `configure!` returns every ring respects the
  accepted keep — and `elide-just-crossed-trace-events` can go on eliding only
  the one record each append pushes out of the window. A raise restores
  nothing: a dropped stream is gone. Returns nil."
  [trace-events-keep]
  (swap! histories
         (fn [histories-map]
           (reduce-kv (fn [acc frame-id history]
                        (let [cut (- (count history) trace-events-keep)]
                          (assoc acc frame-id
                                 (into []
                                       (map-indexed (fn [record-index record]
                                                      (cond-> record
                                                        (< record-index cut)
                                                        (dissoc :trace-events))))
                                       history))))
                      {}
                      histories-map)))
  nil)

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
;;
;;   listeners   cb-id → callback fn
;;
;; Registrations are process-global: a callback outlives every frame it
;; receives records from, and re-registering an id replaces its callback in
;; one swap. A tool that wants to forget a destroyed frame listens for
;; `:rf.frame/destroyed`, which states that fact on the trace stream.

(defonce ^:private listeners (atom {}))

(defn put-listener!
  "Install or replace `f` under `id`. Returns the id."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn drop-listener!
  "Remove the listener registered under `id`."
  [id]
  (swap! listeners dissoc id)
  nil)

(defn reset-listeners!
  "Drop every registered listener."
  []
  (reset! listeners {})
  nil)

(defn listeners-snapshot
  "Return the current `{cb-id → callback}` registry map. Callers iterate this
  snapshot for fan-out, which isolates iteration from concurrent
  `put-listener!` / `drop-listener!` updates."
  []
  @listeners)

;; ---- frame-incarnation ownership -----------------------------------------
;;
;; Epoch stores are addressed publicly by frame id, so every incarnation of an
;; id shares their keys. This private token ledger records which incarnation
;; owns them, and claim, commit and final cleanup share one serialization: a
;; fresh token's claim first clears a predecessor's leftovers, and a commit
;; lands only for the owning token. A destroy holds the id's reservation
;; through its final epoch hook, so that cleanup never meets a successor. Work
;; outside any drain, such as a Tool-Pair replace, can see a successor seated
;; part-way through, and its commit is refused.

(defonce ^:private frame-owner-tokens (atom {}))

#?(:clj
   (defonce ^:private frame-owner-lock (Object.)))

(defn- with-frame-owner-lock [f]
  #?(:clj  (locking frame-owner-lock (f))
     :cljs (f)))

(defn- drop-frame-epoch-state!
  "Drop every epoch side-table entry owned under `frame-id`.

  Caller holds the frame-owner serialization. Listener registrations are
  process-wide and survive."
  [frame-id]
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
  ([frame-id owner-token]
   (claim-frame-owner! frame-id owner-token (constantly true)))
  ([frame-id owner-token continue?]
   (boolean
    (when (and frame-id owner-token (continue?))
      ;; The common path is a re-claim by the current incarnation. Avoid the
      ;; global debug-only serialization on every captured trace; the locked
      ;; path rechecks before replacing so this fast read cannot weaken the
      ;; same-id hand-off.
      (if (identical? (get @frame-owner-tokens frame-id) owner-token)
        (continue?)
        (with-frame-owner-lock
          (fn []
            ;; Recheck exact frame liveness INSIDE the same serialization that
            ;; terminal cleanup uses. Cleanup-before-claim rejects; claim-before-
            ;; cleanup is subsequently cleared by the waiting destroy.
            (when (continue?)
              (let [current (get @frame-owner-tokens frame-id)]
                (when-not (identical? current owner-token)
                  (drop-frame-epoch-state! frame-id)
                  (swap! frame-owner-tokens assoc frame-id owner-token)))
              true))))))))

(defn commit-frame-owner-record!
  "Atomically publish `record` and its last-settled anchor only while
  `owner-token` still owns the frame's epoch stores. Shares the exact owner
  serialization with claim and terminal cleanup: commit-before-cleanup is
  erased, cleanup-before-commit rejects, and B-before-commit rejects. No
  callback runs inside this transaction.

  ONLY AN `:ok` RECORD MOVES THE ANCHOR. `last-settled-epoch` names
  the last epoch that actually SETTLED, which is what restore rewinds to and
  what a post-settle observation back-fills onto; a `:halted-depth` /
  `:halted-destroy` record describes an event that never ran and never settled,
  so it is not a candidate for either. Letting one take the anchor would make
  `restore-epoch!` refuse the newest epoch — `:rf.epoch/restore-non-ok-record`
  — even though the state it names is the live state, and would point
  `commit-halt-record!`'s own durable-snapshot lookup at a record carrying no
  write of its own. The halt record is still appended to the ring: it stays
  fully visible to devtools as the \"drain halted here\" marker, which is its
  entire purpose. It simply does not get to claim it is the settled state."
  [frame-id owner-token record]
  (boolean
    (with-frame-owner-lock
      (fn []
        (when (identical? (get @frame-owner-tokens frame-id) owner-token)
          (record! record)
          (when (= :ok (get record :outcome :ok))
            (set-last-settled-epoch! frame-id (:epoch-id record)))
          true)))))

(defn cleanup-frame-owner!
  "Drop `frame-id`'s ownership entry and every epoch store under it.

  Runs under the serialization `claim-frame-owner!` and
  `commit-frame-owner-record!` share, so a claim or commit racing it lands
  wholly before the drop or wholly after it. It compares no owner token.
  The one caller is the epoch contribution's after-dissoc publication
  (`listeners/on-frame-destroyed!`), which runs while the destroy still holds
  the id's reservation: no same-id
  incarnation can have claimed the stores, so everything under `frame-id` is
  the dying incarnation's to drop, and a token other than its own could only
  be a dead incarnation's."
  [frame-id]
  (with-frame-owner-lock
    (fn []
      (swap! frame-owner-tokens dissoc frame-id)
      (drop-frame-epoch-state! frame-id)
      nil)))

(defn reset-frame-owners!
  "Clear the private incarnation ledger for fixture/global history reset."
  []
  (reset! frame-owner-tokens {})
  nil)
