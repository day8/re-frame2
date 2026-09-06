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
;; `epoch-history` / `projected-history`, and it is a live `restore-epoch!` /
;; `replay-epoch!` target. Swapping the config before the prune makes that
;; escape self-repairing for a POSITIVE depth — the next append re-caps the
;; ring — but PERMANENT at depth 0, where `record!` never appends again. And
;; "a later append repairs it" was never the promise: the promise is about the
;; state at `configure!`'s RETURN. Symmetrically, an anchor published at the
;; seam between the prune and the reconciliation is judged against a snapshot
;; taken before its record existed, so a CORRECT anchor is discarded as
;; unretained.
;;
;; One monitor closes both: it is held across each whole sequence, so
;; `configure!` cannot return while a writer still holds a stale depth, and no
;; anchor decision is taken against a stale ring.
;;
;; DEADLOCK-FREE BY CONSTRUCTION, and that is why it needs no place in the
;; ledger lock order documented further down (nor in the `:drain-lock`
;; argument beside it). This is a LEAF: every section it guards is atom swaps
;; over the epoch stores and nothing else — no other lock is acquired
;; underneath it and no foreign code runs there (`:redact-fn` is STORED here,
;; never invoked). A lock that acquires nothing cannot supply the second edge
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

(declare enforce-depth!)

(defn merge-config!
  "Validate and merge an `opts` map into the live config atom. Returns
  nil. Silently drops invalid slot values (`:depth` /
  `:trace-events-keep` must be non-negative integers; `:redact-fn`
  accepts `fn?` or `nil` for explicit-clear; anything else is dropped).

  Validation at this boundary keeps numeric assumptions out of the hot path.

  An ACCEPTED `:depth` is enforced against the rings that already exist,
  not only against future appends — see `enforce-depth!`. The two numeric
  knobs deliberately differ here: `:depth` is a bound on what the ring
  HOLDS, so it is applied at this boundary, whereas `:trace-events-keep`
  bounds how far back raw traces survive and stays an append-time rule (no
  retained record has its payload rewritten by a config change).

  The config swap and that enforcement are ONE step under the retention lock,
  so no writer holding the previous depth can commit between them — see the
  retention-serialization section above."
  [opts]
  (when (map? opts)
    (let [numeric-options (select-keys opts [:depth :trace-events-keep])
          valid-numeric-options (into {}
                                      (filter (fn [[_ option-value]]
                                                (non-neg-int? option-value)))
                                      numeric-options)
          ;; :redact-fn validated separately — accept fn? OR nil
          ;; (explicit-clear); anything else silently dropped.
          ;; `contains?` distinguishes 'absent slot' from 'present
          ;; nil' so the explicit-clear path lands while a callsite
          ;; that didn't mention :redact-fn doesn't clobber a
          ;; previously-installed fn.
          valid-redact-option (when (contains? opts :redact-fn)
                                (let [redact-fn-value (:redact-fn opts)]
                                  (when (or (nil? redact-fn-value)
                                            (fn? redact-fn-value))
                                    {:redact-fn redact-fn-value})))
          valid-options (merge valid-numeric-options valid-redact-option)]
      (when (seq valid-options)
        (with-retention-lock
          (fn []
            (swap! config merge valid-options)
            ;; Config first, then the rings — but the ordering is no longer
            ;; what carries the invariant. Both are inside the retention
            ;; lock, which is what makes the prune un-undoable: a `record!`
            ;; that read the previous depth cannot be mid-flight here, and
            ;; one that starts after this section reads the new depth.
            (when (contains? valid-options :depth)
              (enforce-depth! (:depth valid-options))))))))
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
  already elided its own just-crossed record; runtime reductions of
  `trace-events-keep` via `(rf/configure! {:epoch-history ...})` take full
  effect on subsequent appends rather than retroactively rewriting the buffer."
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
  necessary cost of bounded heap; the prior O(1) `subvec` view was O(1)
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

  `render-deps` may be nil when the view's read-set was never learned — then only the
  render-key (trace) match applies, and the structured fallback yields nothing.
  Prefers the trace source when present so the render-key precision is kept;
  falls back to the structured rows only when traces are absent."
  [record render-key render-deps]
  (if (contains? record :trace-events)
    (some (fn [trace-event]
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
    (and render-deps
         (some (fn [sub-run-row]
                 (and (true? (:value-changed? sub-run-row))
                      (contains? render-deps (:sub-id sub-run-row))))
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
        record-index (epoch-index history epoch-id)]
    (boolean
      (when record-index
        (some (fn [render-row]
                (= render-key (:render-key render-row)))
              (:renders (nth history record-index)))))))

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
        ;; pre-swap index if an append/eviction interleaved). nil when the
        ;; epoch was not (or no longer) in the ring.
        updated-histories    (swap! histories update-histories)
        updated-history      (get updated-histories frame-id)
        updated-record-index (epoch-index updated-history epoch-id)]
    (when updated-record-index
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
  (let [buffered-events (get @capture-buffers frame-id [])]
    (swap! capture-buffers dissoc frame-id)
    buffered-events))

(defn- run-start-dispatch-id
  "The `:dispatch-id` of the event being settled, read off the FIRST
  `:rf.event/run-start` emit in the buffer. nil when no run-start fired (a
  rejected / aborted dispatch, or a halt path whose event never ran) — the
  no-run-start branch of `harvest-buffer-for-event!` then falls back to the
  settling envelope's dispatch-id to scope its drop."
  [trace-events]
  (some (fn [trace-event]
          (when (= :rf.event/run-start (:operation trace-event))
            (-> trace-event :tags :rf.trace/dispatch-id)))
        trace-events))

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
   (let [buffered-events (get @capture-buffers frame-id [])]
     (if-let [run-dispatch-id (run-start-dispatch-id buffered-events)]
       ;; Return matching traces, retain non-nil ids for later events, and drop
       ;; nil-id orphans. The capture seam normally removes orphans first; this
       ;; partition is the defensive bound if one reaches storage.
       (let [settling-events
             (filterv (fn [trace-event]
                        (= (-> trace-event :tags :rf.trace/dispatch-id)
                           run-dispatch-id))
                      buffered-events)
             ;; Other-event markers: a non-nil dispatch-id that isn't the
             ;; settling event's. Kept verbatim for the child's own settle.
             remaining-events
             (filterv (fn [trace-event]
                        (let [event-dispatch-id
                              (-> trace-event :tags :rf.trace/dispatch-id)]
                          (and (some? event-dispatch-id)
                               (not= event-dispatch-id run-dispatch-id))))
                      buffered-events)]
         ;; Leave the other-event markers in the buffer for their own event's
         ;; settle; drop orphans (nil dispatch-id) and the harvested settling
         ;; events.
         (if (seq remaining-events)
           (swap! capture-buffers assoc frame-id remaining-events)
           (swap! capture-buffers dissoc frame-id))
         settling-events)
       ;; No run-start means no event ran, so nothing can be committed.
       (if settling-dispatch-id
         ;; Scope the drop to THIS dispatch: drop its own traces (the rejection's
         ;; frame-stamped error trace, `:dispatch-id` = settling) + orphans,
         ;; RETAIN any unrelated sibling marker for its own settle, RETURN [] so
         ;; `settle!` commits no misleading `:ok` epoch. Symmetric with the
         ;; run-start branch.
         (let [remaining-events
               (filterv (fn [trace-event]
                          (let [event-dispatch-id
                                (-> trace-event :tags :rf.trace/dispatch-id)]
                            (and (some? event-dispatch-id)
                                 (not= event-dispatch-id settling-dispatch-id))))
                        buffered-events)]
           (if (seq remaining-events)
             (swap! capture-buffers assoc frame-id remaining-events)
             (swap! capture-buffers dissoc frame-id))
           [])
         ;; 1-arity fallback (no envelope id — a direct low-level test call):
         ;; full read-and-clear, returning the whole buffer.
         (do (swap! capture-buffers dissoc frame-id)
             buffered-events))))))

(defn drop-frame-buffer!
  "Drop the frame's in-flight capture buffer."
  [frame-id]
  (swap! capture-buffers dissoc frame-id)
  nil)

(defn drop-dispatch-buffer-events!
  "Drop only capture events owned by `dispatch-id` from `frame-id`'s buffer.

  Used when a dequeued event's exact frame incarnation vanished before its
  tail settled. A fresh same-id frame may already be capturing unrelated work,
  so whole-buffer cleanup would be just as corrupting as committing the stale
  event; this dispatch-id partition removes A's residue and preserves B."
  [frame-id dispatch-id]
  (when dispatch-id
    (swap! capture-buffers
           (fn [buffers]
             (let [remaining-events
                   (filterv #(not= dispatch-id
                                   (-> % :tags :rf.trace/dispatch-id))
                            (get buffers frame-id []))]
               (if (seq remaining-events)
                 (assoc buffers frame-id remaining-events)
                 (dissoc buffers frame-id))))))
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
;; rf2-f8wu. Per Tool-Pair §Time-travel "Bounded history" the runtime keeps
;; the last N epochs per frame and older epochs are discarded, and the
;; depth-0 bullet is explicit that disabling the ring makes `epoch-history`
;; return `[]`. Both are statements about the CURRENT depth, so a reduction
;; has to bite when it is accepted rather than on some later append.
;;
;; Enforcing only at append time left the excess reachable by two different
;; routes, and closing one without the other would still be a defect. It
;; stayed QUERYABLE — `epoch-history` and `projected-history` both read the
;; ring vector directly — and it stayed RESTORABLE, because `restore-epoch!`
;; and `replay-epoch!` resolve their targets off that same vector, so an id
;; the operator believed retired still rewound the frame to state the app had
;; moved past. Depth 0 was permanent as well as sharp: `record!` skips
;; `append-record` entirely at depth 0, so no later append ever arrived to
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
  committed record. An earlier revision left that gap open and called the
  excess transient, which it is for a positive depth and is NOT at depth 0 —
  `record!` appends nothing there, so no later append ever re-caps the ring
  (rf2-f8wu post-merge audit). Returns nil."
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

;; ---- delayed predecessor-silencing lineage (rf2-vxgfnd.265 / .285) ---------
;;
;; A destroyed incarnation A's `:rf.epoch.cb/silenced-on-frame-destroy` fan can
;; be DEFERRED past a same-id successor B's whole lifecycle: A's post-dissoc
;; publish hook is held while B — constructable only after dissoc — claims,
;; settles/re-arms, and even destroys. #5872 gated the WHOLE fan on one coarse
;; `cleanup-frame-owner!` result, conflating three DISTINCT events — store claim,
;; callback delivery/re-arm, and terminal silence. .265 decided each silence PER
;; callback-generation identity; .285 makes that lineage additionally EXACT,
;; LINEARIZABLE, and BOUNDED. Deciding PER identity needs two kinds of evidence:
;;
;;   * delivery/re-arm (live): a successor that re-armed a cb and is STILL LIVE
;;     leaves that cb in the live observation ledger (`cbs-observing-frame`), so
;;     the callback is live on the successor rather than silent.
;;   * terminal-silence (survives cleanup): a successor that re-armed then
;;     DESTROYED already emitted the one truthful silence AND dropped its live
;;     observation on cleanup — so the live ledger no longer shows it. A
;;     monotonic mark records that a `(frame, cb)` silence fired and is NOT
;;     dropped by `cleanup-frame-owner!`; it survives long enough for a paused
;;     predecessor to see it and NOT re-emit the identical unqualified signal
;;     (the A→B→nil ABA). A fresh delivery to the cb supersedes the mark
;;     (`record-observation!` prunes it — a new continuum will owe its own
;;     silence).
;;
;; A snapshots a `baseline` seq BEFORE dissoc (a same-id B is constructable only
;; AFTER dissoc, so any successor silence is stamped strictly after A's baseline).
;; A mark ABOVE that baseline is a successor's → A skips; a mark at/below is a
;; superseded earlier continuum, which the live-observation and generation checks
;; already resolve.
;;
;; The three .285 properties and where each is enforced:
;;
;;   EXACT — the owed `{cb-id → generation}` map is derived from ONE consistent
;;     (listeners, observed) read (`snapshot-terminal-observers`) with the
;;     generation taken from the OBSERVATION STAMP, never a second registry
;;     re-read. A replacement landing mid-snapshot can therefore never attribute
;;     A's observation to a fresh generation H it never observed under.
;;   LINEARIZABLE — eligibility AND the mark write are ONE atomic operation
;;     (`claim-and-publish-delayed-silence!`'s reservation half, under
;;     `silence-lock`): it rechecks the current generation, the CURRENT live
;;     observers (fresh, not a stale pre-loop set),
;;     and the existing mark, then reserves a fresh monotonic seq BEFORE external
;;     delivery. The seq is the single total order the observer relies on; two
;;     overlapping same-id publishers can never both claim. A reservation is
;;     rolled back only if envelope delivery itself throws.
;;   BOUNDED — a per-frame outstanding-predecessor counter
;;     (`open-`/`close-silence-lineage!`) brackets the deferred window. Marks for
;;     a frame are retained ONLY while a deferred predecessor of THAT frame is
;;     outstanding; when the last one resolves the frame's marks are reclaimed,
;;     so a persistent callback destroying unboundedly many unique frame ids does
;;     not accrete a permanent tombstone per id.

(defonce ^:private terminal-silence-seq (atom 0))

(defn next-terminal-silence-seq
  "Mint a fresh, process-monotonic terminal-silence marker."
  []
  (swap! terminal-silence-seq inc))

(defn current-terminal-silence-seq
  "Read the current terminal-silence marker — a destroying predecessor's
  pre-dissoc baseline. A same-id successor's silence, stamped only after that
  predecessor snapshots, always exceeds it."
  []
  @terminal-silence-seq)

(defonce ^:private terminal-silence-marks
  ;; frame-id → {cb-id → terminal-silence-seq of the latest silence emitted for
  ;; (frame, cb)}. Survives compare-owned cleanup so a paused predecessor cannot
  ;; re-emit a silence a successor already delivered; pruned when a fresh
  ;; delivery re-arms the cb (`record-observation!`) or the cb unregisters.
  (atom {}))

(defn- prune-terminal-silence-mark!
  "Drop the terminal-silence mark for `(frame-id, cb-id)` — a fresh delivery to
  the cb has re-armed it, so the prior continuum's mark is superseded."
  [frame-id cb-id]
  (swap! terminal-silence-marks
         (fn [m]
           (if-let [cbs (get m frame-id)]
             (let [cbs' (dissoc cbs cb-id)]
               (if (empty? cbs')
                 (dissoc m frame-id)
                 (assoc m frame-id cbs')))
             m)))
  nil)

(defn drop-cb-silences!
  "Forget one cb's terminal-silence marks across every frame — invoked when the
  listener unregisters (its identity is gone; no predecessor can owe it a
  silence any longer)."
  [cb-id]
  (swap! terminal-silence-marks
         (fn [m]
           (reduce-kv (fn [acc frame-id cbs]
                        (let [cbs' (dissoc cbs cb-id)]
                          (if (empty? cbs')
                            (dissoc acc frame-id)
                            (assoc acc frame-id cbs'))))
                      {}
                      m)))
  nil)

;; The delayed-silence ledger spans three atoms — `listeners` (the
;; callback+generation registry), `observed-frames-by-cb` (the observation
;; ledger), and `terminal-silence-marks` (the lineage marks). Two ORDERED
;; monitors serialise it, split by which atom each op mutates:
;;
;;   listener-registry-lock  guards the `listeners` generation publish/drop.
;;   silence-lock            guards the observation ledger, the terminal-silence
;;                           marks, and the outstanding-lineage brackets.
;;
;; GLOBAL LOCK ORDER: listener-registry-lock BEFORE silence-lock, never the
;; reverse. Any path needing both (the delayed-silence claim, `drop-listener!`,
;; `reset-listeners!`) acquires the registry lock first — `with-claim-locks`
;; encodes that order once.
;;
;; Why TWO monitors and not one (rf2-9bhne6 deadlock follow-up): the single-lock
;; predecessor put `put-listener!`'s `(swap! listeners …)` — and hence any atom
;; WATCHER that swap fires — AND `record-observation!`'s re-arm on the SAME
;; monitor. A listeners-atom watch that parked mid-`swap!` (a JVM deschedule, or
;; a test barrier) then held that monitor while a concurrent `record-observation!`
;; fan-out blocked on it forever — a hard deadlock CI reproduced. Splitting the
;; domains keeps `put-listener!` on the registry lock ALONE, so a registration
;; paused inside its swap can never block a fan-out re-arm (which takes only
;; silence-lock).
;;
;; The generation-authority guarantee (rf2-9bhne6, as CORRECTED by rf2-8b9twg):
;; `eligible-and-reserve!` (under `claim-and-publish-delayed-silence!`) holds BOTH
;; locks across its eligibility recheck AND the mark RESERVATION — so a concurrent
;; `put-listener!` (registry lock) and a concurrent `record-observation!`
;; (silence-lock) are excluded from THAT window and can neither corrupt the three
;; eligibility reads nor let two publishers both reserve. The external emit then
;; runs OUTSIDE both locks (see `claim-and-publish-delayed-silence!` for why: the
;; foreign trace fan-out can reach a frame's `:drain-lock`, and holding a ledger
;; lock across it is an AB-BA deadlock). Authority survives the lock-free emit
;; through DATA QUALIFIERS the receiver re-reads at receipt time, not a lock. A
;; replacement, an `unregister-epoch-listener!` drop, a fresh frame destroy, or a
;; `record-observation!` re-arm MAY all land BETWEEN the reservation and the
;; publication, and they split into TWO kinds:
;;
;;   * REGISTRATION-identity mutations (a replacement, a drop) make a DIFFERENT
;;     generation current. The emit carries `:observed-gen` (the reserved
;;     generation), so the superseded late emit self-filters at the receiver.
;;   * OBSERVATION-continuum mutations (a `record-observation!` re-arm by a
;;     same-id SUCCESSOR frame) mint NO generation — a delivery never
;;     re-registers — so `:observed-gen` still MATCHES while the callback is live
;;     again. `:observed-gen` alone would accept a silence for a live callback
;;     (rf2-qg98y). This kind is discriminated by the observation-continuum half
;;     of the receiver decision (`live-observer?`).
;;
;; The receiver weighs BOTH kinds in ONE operation — `silence-current?`, exposed
;; publicly as `re-frame.epoch/epoch-silence-current?` — which reads the
;; registration generation and the observation continuum inside a single
;; `with-claim-locks` section. It is one operation and not two composable queries
;; because the composite of two independent reads is not linearizable: a
;; replacement or drop landing between them yields an accept for a generation
;; that is already superseded (rf2-uhouu).
;;
;; Ordinary fan-out never contends on either lock: the registry/observation atoms
;; keep their own lock-free swaps for the common path.
#?(:clj
   (defonce ^:private listener-registry-lock (Object.)))

#?(:clj
   (defonce ^:private silence-lock (Object.)))

(defn- with-listener-registry-lock [f]
  #?(:clj  (locking listener-registry-lock (f))
     :cljs (f)))

(defn- with-silence-lock [f]
  #?(:clj  (locking silence-lock (f))
     :cljs (f)))

(defn- with-claim-locks
  "Acquire BOTH ledger monitors in the global order (registry → silence) for the
  delayed-silence claim and the registry+observation wipes (`drop-listener!` /
  `reset-listeners!`). The claim reads the `listeners` generation AND the
  observation/mark ledger and must exclude both `put-listener!` and
  `record-observation!` across its eligibility recheck and mark RESERVATION (NOT
  the external emit, which the claim runs after releasing both locks — rf2-8b9twg);
  the wipes mutate both domains. Encoding the order here keeps every both-locks
  caller consistent so the registry→silence DAG can never invert."
  [f]
  (with-listener-registry-lock #(with-silence-lock f)))

;; frame-id → count of deferred predecessors of THAT frame whose terminal
;; evidence is currently outstanding (snapshotted but not yet published). A
;; `(frame, cb)` mark can only ever be consulted by a deferred predecessor OF
;; THAT SAME frame (`claim-and-publish-delayed-silence!` reads marks keyed by its
;; own frame-id), so the frame's marks are needed only while its count is positive.
;; When the last outstanding predecessor of a frame resolves, the frame's marks
;; are reclaimed — this is what bounds `terminal-silence-marks` under a persistent
;; callback that observes and destroys unboundedly many unique frame ids
;; (rf2-vxgfnd.285). Marks for OTHER frames are untouched, so a held predecessor
;; keeps only the marks it can still consume.
(defonce ^:private outstanding-silence-lineages (atom {}))

(defn open-silence-lineage!
  "Open the deferred-silence window for one destroyed incarnation of `frame-id`.
  Called by `snapshot-terminal-destroy-evidence!` when it binds A's owed evidence
  before dissoc, so the frame's terminal-silence marks survive until A publishes.
  Balanced by exactly one `close-silence-lineage!`."
  [frame-id]
  (with-silence-lock
    (fn []
      (swap! outstanding-silence-lineages update frame-id (fnil inc 0))
      nil)))

(defn close-silence-lineage!
  "Close one deferred-silence window for `frame-id`. When the frame's last
  outstanding predecessor resolves, no paused predecessor of the frame can
  consult its marks, so they are reclaimed — the boundedness guarantee. Balances
  exactly one `open-silence-lineage!`."
  [frame-id]
  (with-silence-lock
    (fn []
      (let [remaining (swap! outstanding-silence-lineages
                             (fn [m]
                               (let [n (dec (get m frame-id 0))]
                                 (if (pos? n)
                                   (assoc m frame-id n)
                                   (dissoc m frame-id)))))]
        (when-not (contains? remaining frame-id)
          (swap! terminal-silence-marks dissoc frame-id)))
      nil)))

(defn reset-frame-silences!
  "Wipe every terminal-silence mark and every outstanding-predecessor bracket for
  fixture/global reset.

  The monotonic `terminal-silence-seq` is deliberately NOT reset. Recycling it to
  0 could make a post-reset successor's mark compare at/below an outstanding
  predecessor's earlier baseline, letting that predecessor re-emit a silence the
  successor already fired (rf2-vxgfnd.285). The seq is a plain process-monotonic
  comparison domain — its absolute value is never observed, only ordering — so
  letting it climb across resets is free and keeps the domain non-recycling."
  []
  (reset! terminal-silence-marks {})
  (reset! outstanding-silence-lineages {})
  nil)

(defn- live-observer?
  "True when `cb-id`'s CURRENT generation observes `frame-id` right now — the
  observation stamp for the frame equals the cb's live generation token. Read
  fresh (both derefs here), so a successor re-arming a cb mid-fan is honoured the
  instant it lands rather than against a stale pre-loop set (rf2-vxgfnd.285).

  NOT SELF-COHERENT — every caller MUST hold both ledger locks (rf2-uhouu). The
  two derefs below are SEPARATE reads of two atoms guarded by DIFFERENT monitors:
  the observation ledger (silence-lock) and then the listener registry (registry
  lock). A same-id replacement plus a re-arm of the fresh generation landing
  BETWEEN them is read as stamp=G against registry=H — `false`, an answer neither
  the before-state (stamp G, registry G → true) nor the after-state (stamp H,
  registry H → true) ever had. Under `with-claim-locks` both mutations are
  excluded, so the pair is one consistent snapshot.

  Both callers hold the locks: `eligible-and-reserve!` (the reservation's second
  eligibility check) and `silence-current?` (the receiver decision). It is
  deliberately PRIVATE — this fact is not independently publishable, because any
  consumer composing it with a separate generation read reconstructs exactly the
  torn decision the locks exist to prevent."
  [frame-id cb-id]
  (let [token (get-in @observed-frames-by-cb [cb-id frame-id] ::absent)]
    (and (not= token ::absent)
         (= token (:generation (get @listeners cb-id))))))

(defn silence-current?
  "THE receiver decision for a `:rf.epoch.cb/silenced-on-frame-destroy` signal:
  true when the silence reserved for `(frame-id, cb-id)` under generation
  `observed-gen` still names a CURRENT fact — the callback's live registration is
  still the one the silence was owed to, and nothing has re-armed it on that
  frame. Backs the public `re-frame.epoch/epoch-silence-current?`.

  ONE LINEARIZATION POINT (rf2-uhouu). The decision needs two facts:

    * REGISTRATION identity — `observed-gen` still names the live registration
      (a replacement or an `unregister-listener!` drop in the reserve→emit window
      makes a DIFFERENT generation current, so G's silence no longer describes
      the current callback).
    * OBSERVATION continuum — the callback is not observing the frame right now
      (a same-id SUCCESSOR frame re-arms by DELIVERY, which mints NO generation,
      so `observed-gen` still matches while the callback is live again —
      rf2-qg98y).

  Both are read INSIDE one `with-claim-locks` critical section, which excludes
  `put-listener!` (registry lock), `drop-listener!` / `reset-listeners!` (both)
  and `record-observation!` (silence-lock) for its duration. That is what makes
  the composite answer describe a state the ledger ACTUALLY HAD.

  Reading the two facts through two SEPARATE public queries — the shape this
  supersedes — is NOT linearizable: a replacement or drop landing between them is
  read as generation-still-matches AND not-observing, accepting a silence for a
  registration that is already superseded, an answer no single point in time ever
  had. That is why the two low-level queries were retired rather than kept
  alongside this one: the composite belongs behind the seam, not at every call
  site.

  Cheap and non-blocking by construction: two derefs, no allocation, no foreign
  code, no trace emission inside the locks — so a receiver calling this from
  inside a trace listener cannot reach a frame's `:drain-lock` while holding a
  ledger lock (the AB-BA hazard rf2-8b9twg closed). The reverse direction is safe
  too: nothing that holds a ledger lock waits on a drain-lock, and the silence
  EMIT this decision responds to already released both locks before publishing.

  A `nil` `observed-gen` is never current: a signal that carries no generation
  cannot name a live registration (an unregistered cb also reads `nil`, and
  `nil = nil` would otherwise accept). CLJS is single-threaded — the locks
  compile away and the expression is atomic by construction."
  [frame-id cb-id observed-gen]
  (with-claim-locks
    #(boolean
       (and (some? observed-gen)
            (= observed-gen (:generation (get @listeners cb-id)))
            (not (live-observer? frame-id cb-id))))))

(defn- eligible-and-reserve!
  "The caller MUST already hold BOTH ledger locks (via `with-claim-locks`:
  listener-registry-lock → silence-lock). Reading the `listeners` generation
  under the registry lock and the observation / mark ledger under silence-lock is
  what makes the recheck coherent against a concurrent `put-listener!` AND a
  concurrent `record-observation!`. Recheck delayed-silence eligibility for
  `(frame-id, cb-id)` against the FRESHEST listener /
  observation / mark state and, when eligible, RESERVE a fresh monotonic seq
  (writing the mark inside the caller's critical section) and return it; else
  return nil, writing nothing. Kept separate from
  `claim-and-publish-delayed-silence!` — its sole caller — so the eligibility
  DECISION reads in one place under the locks while the external emit that
  answers it runs outside them.

  The three checks:
    1. `cb-id`'s current generation still equals `observed-gen` — a replacement /
       drop in the deferred window is a fresh generation that never observed A,
       and a callback swapped between eligibility and delivery cannot inherit A's
       unqualified silence.
    2. `cb-id` is NOT a current live observer — a still-live successor that
       re-armed it owns the live callback, not a silence. Read fresh, so a trace
       listener that re-arms this identity earlier in the same fan is honoured.
       This decision is RESERVATION-time only: a re-arm landing after the locks
       release cannot be caught here, and `observed-gen` cannot express it (a
       delivery mints no generation), so the receiver re-reads the SAME predicate
       inside `silence-current?` — public as
       `re-frame.epoch/epoch-silence-current?` (rf2-qg98y, made atomic by
       rf2-uhouu). Note this caller and that one share the locks for the SAME
       reason: `live-observer?` is coherent only under them.
    3. no terminal-silence mark for `(frame, cb)` stands ABOVE `baseline` — a
       successor (or an overlapping same-id publisher) already claimed the one
       signal; re-emitting would be the A→B→nil ABA / concurrent double-signal."
  [frame-id cb-id observed-gen baseline]
  (when (and (= observed-gen (:generation (get @listeners cb-id)))
             (not (live-observer? frame-id cb-id))
             (let [s (get-in @terminal-silence-marks [frame-id cb-id])]
               (or (nil? s) (<= s (or baseline 0)))))
    (let [seq (next-terminal-silence-seq)]
      (swap! terminal-silence-marks assoc-in [frame-id cb-id] seq)
      seq)))

(defn claim-and-publish-delayed-silence!
  "Reserve the one delayed silence for `(frame-id, cb-id)` under BOTH ledger locks
  (`with-claim-locks`: listener-registry-lock → silence-lock), RELEASE the locks,
  then run the external `publish!` OUTSIDE them (rf2-8b9twg).

  `publish!` is a 0-arg thunk that performs the external
  `:rf.epoch.cb/silenced-on-frame-destroy` emit. It runs with NO ledger lock
  held, so any registry/observation mutation MAY land BETWEEN the reservation and
  the emit — a same-id replacement (making a fresh generation H current), an
  `unregister-epoch-listener!` drop, a fresh same-id frame destroy, or a
  `record-observation!` re-arm on a live successor. That window is intended, and
  it is made coherent by ONE receiver-side decision that weighs BOTH KINDS of
  mutation — `silence-current?`, public as `re-frame.epoch/epoch-silence-current?`.

  REGISTRATION identity (a replacement, a drop). `publish!` MUST qualify its emit
  with `observed-gen` (the caller bakes it into the payload): the resulting
  GENERATION-QUALIFIED signal SELF-FILTERS at the receiver — an observer whose
  current generation for `cb-id` no longer equals the carried `observed-gen`
  discards it. So the forbidden ordering (H current, THEN a signal attributed to
  H) is impossible: the signal is attributed to G, and a receiver that sees G is
  no longer current drops it. This SUPERSEDES the emit-under-lock mechanism of
  rf2-9bhne6 — the generation stays authoritative through a data qualifier, not
  through holding a lock across foreign code.

  OBSERVATION continuum (a `record-observation!` re-arm). `observed-gen` does NOT
  cover this kind and must not be claimed to (rf2-qg98y). A delivery mints no
  generation, so a same-id SUCCESSOR frame that re-arms `cb-id` in this window
  leaves the carried `observed-gen` EQUAL to the live generation: the
  generation half alone would accept a silence for a callback that is receiving
  records again. The observation-continuum half closes it — this ledger's
  `live-observer?` for `(cb-id, frame)` — and the signal is discarded when the
  observation is live.

  The two halves are ONE operation, not two queries a receiver composes
  (rf2-uhouu). Composed, they are not linearizable: a replacement or a drop
  landing between the two reads is seen as generation-still-matches AND
  not-observing, accepting a silence for an already-superseded registration — a
  verdict no single point in time ever held. `silence-current?` takes both halves
  under `with-claim-locks`, so its answer always names a real ledger state:

      (re-frame.epoch/epoch-silence-current? tags)

  Both clauses are read at RECEIPT time, so the pair is exact then: the silence is
  current iff both hold. A re-arm landing AFTER the receiver read is simply a
  later continuum, which the receiver observes as fresh record deliveries.

  ## Why the emit MUST run OUTSIDE the ledger locks (rf2-8b9twg)

  `publish!` fans an external `trace/emit!` to ARBITRARY trace listeners, and a
  framework-blessed listener may `dispatch-sync` (the rf2-1zxlsm contract; Xray
  dispatch-syncs from its collector). `dispatch-sync` enters `drain-block!` /
  `call-serialized-with-drain!`, which spin-CAS-acquires the target frame's
  `:drain-lock` (`re-frame.router`, `re-frame.frame`). Meanwhile a thread DRAINING
  a frame holds that frame's `:drain-lock` for the whole pass and, via
  `settle!` → `notify-listeners!` → `record-observation!` re-arm (or a mid-drain
  `drop-listener!` / destroy), acquires `silence-lock`/`registry-lock` UNDER the
  held `:drain-lock`. Holding a ledger lock across the emit therefore inverts the
  ledger locks against `:drain-lock`: hold-silence-lock → want-drain-lock (this
  path) vs hold-drain-lock → want-silence-lock (the drainer) — an AB-BA HARD HANG.
  rf2-9bhne6's deadlock-freedom argument reasoned ONLY about `frame-owner-lock`
  and never considered `:drain-lock` / the router, so it missed this cycle.
  Emitting OUTSIDE both ledger locks removes the foreign-code-under-lock edge
  entirely: the fan-out may reach `:drain-lock` freely because no ledger lock is
  held.

  ## What the reservation-under-lock still buys

  `eligible-and-reserve!` runs under BOTH locks so its three eligibility reads
  (current generation == `observed-gen`, not-a-live-observer, mark-above-baseline)
  stay coherent against a concurrent `put-listener!` (registry lock) and
  `record-observation!` / wipe (silence-lock), and it writes the monotonic mark
  that makes the claim the single linearization point — two overlapping
  publishers can never both reserve. `observed-gen` is validated equal to the
  current generation AT reservation time, and that is the generation `publish!`
  qualifies the emit with.

  Returns true when the silence was reserved+published and nil (the `when-let`
  short-circuit, falsey either way) when ineligible. If `publish!` throws, the
  reservation is rolled back under `silence-lock` (only while it still stands) —
  acquiring silence-lock ALONE keeps the global registry→silence order (no
  inversion) — and the throw propagates.

  Lock-order safety: the cross-lock nesting to `frame-owner-lock` remains a strict
  DAG (no path holds `frame-owner-lock` while acquiring either ledger lock — the
  destroy recipe releases it in `cleanup-frame-owner!` before this fan, and the
  settle path's `notify-listeners!`/`record-observation!` run after
  `commit-frame-owner-record!` returns), AND — the rf2-8b9twg correction — NO path
  now holds either ledger lock while the emit fans to a listener that can acquire
  `:drain-lock`, so the ledger↔`:drain-lock` cycle is gone."
  [frame-id cb-id observed-gen baseline publish!]
  (when-let [reserved (with-claim-locks
                        (fn []
                          (eligible-and-reserve! frame-id cb-id observed-gen baseline)))]
    ;; Locks RELEASED. Emit the generation-qualified signal OUTSIDE both ledger
    ;; locks so the foreign trace fan-out cannot reach a frame's :drain-lock while
    ;; we hold a ledger lock — the ledger↔drain-lock AB-BA deadlock (rf2-8b9twg).
    (try
      (publish!)
      true
      (catch #?(:clj Throwable :cljs :default) ex
        ;; External delivery failed — release OUR reservation (only while it still
        ;; stands) so the one signal can be legitimately re-attempted, then
        ;; propagate contained. Re-acquire silence-lock ALONE (registry→silence
        ;; order preserved) for the prune; no concurrent claim can interleave with
        ;; the compare-and-prune because it is a single silence-lock section.
        (with-silence-lock
          (fn []
            (when (= reserved (get-in @terminal-silence-marks [frame-id cb-id]))
              (prune-terminal-silence-mark! frame-id cb-id))))
        (throw ex)))))

(defn terminal-silence-marks-snapshot
  "Read-only view of the `frame-id → {cb-id → seq}` terminal-silence ledger —
  a boundedness probe for tests."
  []
  @terminal-silence-marks)

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

  Participates in `listener-registry-lock` (rf2-9bhne6, as corrected by
  rf2-8b9twg): installing a fresh generation is a `listeners`-atom mutation that
  `claim-and-publish-delayed-silence!` reads (under the same registry lock) while
  RESERVING a delayed silence, so it is serialized against that reservation's
  eligibility recheck — a replacement cannot make a fresh generation current
  DURING the reservation; it blocks on the registry lock until the reservation
  completes. It does NOT block the silence's external EMIT: that emit runs OUTSIDE
  the ledger locks (rf2-8b9twg), so a replacement MAY land between the reservation
  and the emit and make a fresh generation current. That is sound because the emit
  is generation-qualified (`:observed-gen`): a superseded late emit self-filters at
  the receiver rather than depending on a held lock. It holds ONLY the registry
  lock, NOT silence-lock, so a watcher parked inside the `(swap! listeners …)`
  below can never block a concurrent `record-observation!` fan-out re-arm (the
  single-monitor deadlock, rf2-9bhne6 follow-up).

  Returns the id."
  [id f]
  (with-listener-registry-lock
    (fn []
      (let [g (next-listener-generation)]
        (swap! listeners assoc id {:generation g :callback f}))))
  id)

(defn drop-listener!
  "Remove the listener registered under `id` and any observation
  bookkeeping it carried. Takes BOTH ledger locks (`with-claim-locks`,
  rf2-9bhne6): it mutates the `listeners` registry (registry lock) AND the
  observation ledger + terminal-silence marks (silence-lock). Holding both means
  a drop is serialized against a delayed-silence claim's RESERVATION — it cannot
  retire a generation, or clear an observation the claim is deciding against,
  WHILE the claim's eligibility recheck+reserve runs under those locks. It does
  NOT block the claim's external EMIT (that runs after the locks are released,
  rf2-8b9twg): a drop MAY land between the reservation and the emit, and the
  generation-qualified signal stays correct because a receiver self-filters an
  `:observed-gen` that no longer names a live registration."
  [id]
  (with-claim-locks
    (fn []
      (swap! listeners dissoc id)
      (swap! observed-frames-by-cb dissoc id)
      (drop-cb-silences! id)))
  nil)

(defn reset-listeners!
  "Drop every registered listener and clear all observation bookkeeping —
  registry, observation stamps, AND the terminal-silence lineage. Leaving the
  silence marks behind stranded a tombstone per destroyed frame past a full
  listener wipe (rf2-vxgfnd.285); with every listener gone no predecessor can owe
  a silence, so the whole ledger is cleared here too. Runs under BOTH ledger
  locks (`with-claim-locks`, rf2-9bhne6) — it wipes the `listeners` registry
  (registry lock) and the observation / mark ledger (silence-lock) — so a wipe
  cannot tear a concurrent silence claim's registry / observation / mark reads."
  []
  (with-claim-locks
    (fn []
      (reset! listeners {})
      (reset! observed-frames-by-cb {})
      (reset-frame-silences!)
      nil)))

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
      this token: one deref + lookup, no swap, NO lock.

  Both checks ride INSIDE the swap as well, so the decision is taken against a
  consistent listener snapshot on every CAS retry — a replace/drop landing
  mid-swap is honoured.

  A genuine re-arm (the outer guard passed) runs under `silence-lock`
  (rf2-9bhne6): it mutates the observation ledger AND prunes the terminal-silence
  mark, both of which `claim-and-publish-delayed-silence!` reads (under
  silence-lock, the inner of its two claim locks) while RESERVING a delayed
  silence. Participating in that lock makes the claim's eligibility reads coherent
  against a concurrent re-arm — a re-arm cannot land between the claim's
  individual reads (they run under the lock) and yield a stale-state grant. A
  re-arm MAY still land after the reservation, while the claim's external emit
  runs lock-free (rf2-8b9twg). `:observed-gen` does NOT discriminate that case —
  a re-arm mints no generation, so the emitted qualifier still matches the live
  one (rf2-qg98y). What discriminates it is the OBSERVATION-CONTINUUM clause of
  the receiver decision: this swap is exactly what makes `live-observer?` — the
  observation-continuum half `silence-current?` weighs (public:
  `re-frame.epoch/epoch-silence-current?`) — report the callback LIVE again, so a
  receiver deciding at receipt time discards the superseded silence.
  Crucially it takes ONLY silence-lock, NOT the registry lock
  `put-listener!` holds, so a re-arm is never blocked behind a registration
  paused inside the `listeners` swap (the single-monitor deadlock this split
  fixed, rf2-9bhne6 follow-up). The lock-free fast no-op above keeps the fan-out
  hot path uncontended; only the rare genuine transition takes the lock (and
  re-checks its guards inside, so the pre-lock read cannot weaken the decision).
  The `(get @listeners cb-id)` generation reads here are lock-free w.r.t.
  `put-listener!`: a stale read either matches `token` (records an observation
  the token-scoped readers accept) or not (no-op) — and a since-replaced
  generation self-invalidates because every reader compares the stamp to the live
  generation."
  [cb-id token frame-id]
  (when (and frame-id
             ;; Lock-free fast no-op: an already-stamped (cb, generation, frame)
             ;; triple needs no mutation. Only a genuine transition proceeds to
             ;; take the lock and re-decide inside it.
             (not= token (get-in @observed-frames-by-cb [cb-id frame-id])))
    (with-silence-lock
      (fn []
        (when (and (not= token (get-in @observed-frames-by-cb [cb-id frame-id]))
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
                     (assoc-in m [cb-id frame-id] token))))
          ;; A fresh observation re-arms this cb for the reused id: a new
          ;; continuum begins and will owe its own silence, so a stale terminal-
          ;; silence mark for (frame, cb) is superseded. Pruning it keeps the
          ;; lineage ledger bounded across incarnation churn (rf2-vxgfnd.265). It
          ;; is correctness-preserving either way — a paused predecessor's baseline
          ;; predates this delivery, so a still-present mark would compare below
          ;; the SUCCESSOR's baseline, never falsely gating it.
          (prune-terminal-silence-mark! frame-id cb-id))))))

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

(defn snapshot-terminal-observers
  "Snapshot the destroyed `frame-id`'s owed observers from ONE consistent read of
  the listener registry and the observation ledger, returning
  `{:listeners <live-registry> :observing {cb-id → generation}}`.

  `:observing` carries EACH observing cb's EXACT generation, taken from the
  OBSERVATION STAMP — never a second registry re-read. This is the exactness
  guarantee (rf2-vxgfnd.285): the predecessor's old two-step shape validated a
  generation G through `cbs-observing-frame` and then re-read the registry for
  the generation, so a replacement landing between the two reads could record a
  fresh generation H as having observed A. Deriving the generation from the same
  stamp used to qualify the observer makes that attribution impossible — a cb
  already replaced by snapshot time simply fails the stamp/live-generation match
  and is omitted (its stale silence, if any, is the successor's to decide).

  `:listeners` is the SAME registry read used to qualify the observers, so a
  mid-drain `:halted-destroy` record's listener fan and the owed-observer
  generations can never disagree across two registry reads."
  [frame-id]
  (let [live     @listeners
        observed @observed-frames-by-cb]
    {:listeners live
     :observing (reduce-kv
                  (fn [acc cb-id frames]
                    (let [token (get frames frame-id ::absent)]
                      (if (and (not= token ::absent)
                               (= token (:generation (get live cb-id))))
                        (assoc acc cb-id token)
                        acc)))
                  {}
                  observed)}))

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
  callback runs inside this transaction."
  [frame-id owner-token record]
  (boolean
    (with-frame-owner-lock
      (fn []
        (when (identical? (get @frame-owner-tokens frame-id) owner-token)
          (record! record)
          (set-last-settled-epoch! frame-id (:epoch-id record))
          true)))))

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
  (reset-frame-silences!)
  nil)
