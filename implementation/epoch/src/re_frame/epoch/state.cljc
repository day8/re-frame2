(ns re-frame.epoch.state
  "Shared state for the epoch surface — the eight defonce atoms plus the
  low-level CRUD against them, and the config knob accessors. Per
  rf2-0wi86 (cohesion split): every other seam (`capture`, `assembly`,
  `write`, `listeners`) and the `re-frame.epoch` facade reach the
  shared atoms through this namespace; the atoms themselves stay
  `^:private` so cross-seam access is exclusively through the named
  helpers below.

  The atoms in question:

    config                  per-frame ring-buffer config + redact-fn
    epoch-counter           monotonically-increasing :epoch-id source
    histories               frame-id → vector<:rf/epoch-record>
    last-settled-epoch      frame-id → epoch-id of the most-recently-
                            settled `:ok` epoch (rf2-qs6dl back-fill
                            anchor)
    mount-attribution       frame-id → {render-key → {:epoch-id E
                                                       :deps #{sub-id…}}}
                            (rf2-vh1k3 + rf2-dq2b7 — the mount-anchor
                            and the view's learned read-set share one
                            entry; per Phase-1 finding they never
                            diverge in usage)
    capture-buffers         frame-id → vector<trace-event> (in-flight)
    listeners               cb-id    → fn
    observed-frames-by-cb   cb-id    → #{frame-id ...}

  Per Phase-1 finding (rf2-0wi86): no two atoms are ever held in a
  single critical section, so a tiny state ns owns all of them with
  zero locking/ordering subtleties — the cross-cutting coupling is
  cosmetic, not structural.

  Per rf2-33lpr decision review: the eight-atom shape (post the
  rf2-dq2b7 mount-attribution merge) is the right resting point.
  Two further consolidations were considered and rejected:

    * Per-frame cluster (`histories` + `last-settled-epoch` +
      `mount-attribution` + `capture-buffers` → one `frame-state`
      atom keyed by frame-id). REJECTED: `capture-buffers` is the
      hottest atom (one swap per trace emit, potentially many per
      event); co-locating it with the rarely-touched back-fill atoms
      forces every hot-path swap! to operate on a larger map and
      adds cross-drain contention through a shared swap site (every
      frame's drain now races every other frame's drain on the
      single per-frame-state atom). The per-atom decomposition lets
      the JIT specialise each swap's hot loop, keeps each map small
      (one key class per atom), and isolates contention by signal
      class.

    * Listener cluster (`listeners` + `observed-frames-by-cb` → one
      atom keyed by cb-id). REJECTED: `listeners-snapshot` reads on
      every settle (fan-out target); `observed-frames-by-cb` updates
      lazily on first-sighting only. Co-locating means every settle's
      snapshot deref pulls the larger map, and per-cb writes flow
      through the same swap! site as the registry. The Phase-1
      invariant (rf2-0wi86, no shared critical section) lets the two
      atoms stay separate at zero cost.

  The singletons `config` and `epoch-counter` are irreducible (the
  former is configuration state, the latter a counter source — both
  read/written by ALL frames, so frame-id keying would be artificial).")

;; ---- configuration --------------------------------------------------------

(def ^:private default-depth
  ;; Deep enough to hold a typical debug session's cascade history;
  ;; trades bounded heap for stable time-travel coverage.
  50)

(def ^:private default-trace-events-keep
  ;; Matches `default-depth` so by default trace is retained for every
  ;; retained epoch — when an epoch evicts, its trace evicts too. Per
  ;; Mike pair-debug 2026-05-27 (the previous default of 5 created a
  ;; discrepancy where 50 epoch records were retained but only the
  ;; most recent 5 had their `:trace-events` — operator's mental model
  ;; expects atomic relationship between epoch + trace).
  ;;
  ;; Production builds elide the trace machinery via
  ;; `interop/debug-enabled? false` so this only affects dev sessions.
  ;; Heap cost of 50 trace-events is modest (~thousands of small
  ;; entries; well within browser tolerance).
  ;;
  ;; Memory-conscious hosts can still set a lower cap via
  ;; `(rf/configure :epoch-history {:trace-events-keep N})`. Setting
  ;; the slot to `0` drops every record's `:trace-events`.
  50)

(defonce ^:private config
  ;; Three keys today (:depth, :trace-events-keep, :redact-fn). Map
  ;; shape kept open so future (rf/configure :epoch-history {...})
  ;; extensions don't break the shape. Per rf2-wp70d / Tool-Pair
  ;; §Time-travel §Redaction hook + Security.md §Epoch privacy
  ;; posture: :redact-fn defaults to nil — apps that record
  ;; sensitive material into app-db opt in by installing a fn.
  (atom {:depth             default-depth
         :trace-events-keep default-trace-events-keep
         :redact-fn         nil}))

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

  Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: validation at the
  boundary keeps stored config sane — a `nil` or non-numeric value
  would otherwise survive into `record!` and explode at the next
  `pos?` / `nat-int?` call."
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

  HOT PATH: invoked from `append-record` on every drain settle (every
  user-facing event). Pre-rf2-1e38x this rewrote the whole history
  vector via `map-indexed`; under steady state only one record per
  append actually transitions, so the O(n) walk was wasted work. The
  steady-state invariant holds because every prior append already
  elided its own just-crossed record; runtime reductions of `keep`
  via `(rf/configure :epoch-history ...)` will take full effect on
  subsequent appends rather than retroactively rewriting the buffer
  (pre-alpha posture)."
  [history keep]
  (let [n (count history)]
    (if (and (some? keep) (nat-int? keep) (> n keep))
      (let [idx (- n keep 1)
            r   (nth history idx)]
        (if (contains? r :trace-events)
          (assoc history idx (dissoc r :trace-events))
          history))
      history)))

(defn- append-record
  "Conj `record` onto the frame's history vector, cap to `d` via
  `subvec` (cheap structural reuse — no copy), then elide the just-
  crossed record's `:trace-events` per `keep`.

  HOT PATH: fires once per cascade settle, i.e. once per dispatched
  user event under steady state. Cost is O(1) in both the depth cap
  and the trace-events elision — the vector grows by one, optionally
  drops its leftmost element via `subvec`, and at most one record's
  `:trace-events` slot is dissoc'd."
  [history record d keep]
  (let [history+ (conj (or history []) record)
        n        (count history+)
        capped   (if (and (pos? d) (> n d))
                   (subvec history+ (- n d))
                   history+)]
    (elide-just-crossed-trace-events capped keep)))

(defn record!
  "Append a record into the frame's history. The depth cap and the
  `:trace-events-keep` cap are read from the config atom on each
  append so runtime `(rf/configure :epoch-history ...)` takes effect
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
  epoch?` reuses it for its record lookup. `write/find-epoch-in` answers
  the sibling 'give me the record' question off a deref'd history."
  [history epoch-id]
  (some (fn [i]
          (when (= epoch-id (:epoch-id (nth history i)))
            i))
        (range (count history))))

;; ---- post-settle render back-fill (rf2-qs6dl) -----------------------------
;;
;; A `:view/render` / `:rf.view/rendered` trace fires at React COMMIT
;; time, which lands AFTER the causing cascade's run-to-completion has
;; settled (Reagent batches re-renders onto a later tick — see
;; `re-frame.interop/after-render`). By commit time `settle!` has already
;; harvested the cascade buffer and committed the record, so the render
;; emit lands in a now-empty buffer and — pre-rf2-qs6dl — was harvested
;; by the NEXT cascade's settle, mis-attributing every render to cascade
;; N+1 (the one-epoch lag the bead documents).
;;
;; The fix attributes the render to the cascade that CAUSED it: when a
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
  render emits can be attributed back to this cascade (rf2-qs6dl)."
  [frame-id epoch-id]
  (when (and frame-id epoch-id)
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

;; ---- mount-epoch tracking + render-attribution resolution (rf2-vh1k3) ----
;;
;; rf2-qs6dl back-fills a post-settle render to the frame's most-recently-
;; settled epoch — the cascade that ran just before the React commit. That
;; is correct for a genuine reactive re-render, but WRONG for a MOUNT
;; render whose commit lands late: React/Reagent batch a freshly-mounted
;; component's render onto a later tick, so a view's mount render can
;; commit AFTER the first user interaction settles. Attributed to
;; last-settled, it shows up spuriously in the first post-mount cascade's
;; RENDERED list (e.g. parallel-frames' title-view appearing in the first
;; counter-inc epoch even though its subs never changed).
;;
;; The discriminator is the rf2-l1jz8 `:value-changed?` attribution
;; already on the reactive `:sub/run` trace, plus the rf2-vh1k3
;; `:reader-render-key` stamp naming the view whose render deref'd the
;; sub. A render of view K belongs to epoch N iff N's cascade actually
;; drove K's re-render — i.e. some `:sub/run` with `:reader-render-key K`
;; AND `:value-changed? true` landed in N. When no recent epoch shows a
;; value-change for K, the render is a mount (or mount-burst tail) and is
;; attributed to K's MOUNT epoch (its first-ever attribution) rather than
;; whatever cascade happens to be settling.

;; Per rf2-dq2b7: the two former atoms (`mount-epoch-by-render-key` ::
;; `{frame-id {render-key epoch-id}}` and `render-deps-by-render-key` ::
;; `{frame-id {render-key #{sub-id…}}}`) were merged into a single
;; `mount-attribution` atom keyed identically (frame-id × render-key)
;; carrying both signals under one entry. The two atoms NEVER diverged
;; in usage: both pruned in lockstep by `drop-frame-mount-attribution!`,
;; both wiped together by `reset-mount-attribution!`. Collapsing them
;; halves the defonce count (Phase-1 finding rf2-0wi86 §two atoms) and
;; halves the swap! count on frame teardown without changing any
;; observable semantic.
;;
;; The four accessor names (`mount-epoch-for` / `record-mount-epoch!`
;; and `render-deps-for` / `record-render-deps!`) survive as the public
;; surface — each is semantically distinct (one reads/writes the
;; mount-anchor, the other the read-set) — but they project from one
;; underlying map.
;;
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
  (rf2-vh1k3) — the synchronous in-render deref that names the reading
  view. Idempotent; the set only grows."
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

(defn drop-frame-mount-attribution!
  "Forget every render-key's mount-anchor + read-set for `frame-id`
  (frame teardown). Replaces the four-wiper pair
  `drop-frame-mount-epochs!` + `drop-frame-render-deps!` (rf2-dq2b7)."
  [frame-id]
  (swap! mount-attribution dissoc frame-id)
  nil)

(defn reset-mount-attribution!
  "Wipe the mount-attribution map across all frames (fixture reset).
  Replaces the two-reset pair `reset-mount-epochs!` +
  `reset-render-deps!` (rf2-dq2b7)."
  []
  (reset! mount-attribution {})
  nil)

(defn- epoch-value-changed-for-view?
  "True when epoch record `r` carries a value-changed `:sub/run` belonging
  to the view named by `render-key` — i.e. a `:sub/run` with
  `:value-changed? true` whose `:reader-render-key` is `render-key`
  (the synchronous in-render deref, when stamped) OR whose `:sub-id` is
  in the view's learned read-set `deps` (the post-settle reactive
  recompute, which carries no render-key). `deps` may be nil when the
  view's read-set was never learned — then only the render-key match
  applies."
  [r render-key deps]
  (some (fn [ev]
          (and (= :rf.sub/run (:operation ev))
               (true? (-> ev :tags :rf.sub/value-changed?))
               (let [t (:tags ev)]
                 (or (= render-key (:rf.sub/reader-render-key t))
                     (and deps (contains? deps (:rf.sub/id t)))))))
        (:trace-events r)))

(defn- value-changed-epoch-for
  "Scan `frame-id`'s ring (most-recent first) for the newest epoch in
  which the view named by `render-key` had a value-changed `:sub/run` —
  i.e. the cascade that genuinely re-rendered the view. Returns that
  epoch-id, or nil when no retained epoch shows a value-change for the
  view (a mount / mount-burst tail).

  A view's subs are matched two ways (see `epoch-value-changed-for-view?`):
  by the `:reader-render-key` stamp (synchronous in-render deref) and by
  the view's learned read-set (`render-deps-for`) for post-settle reactive
  recomputes that carry no render-key. The read-set is learned at mount
  from the stamped synchronous derefs; a view's sub set is stable across
  its life, so mount-time learning suffices.

  Reads only `:trace-events`, which the most-recent `:trace-events-keep`
  records retain — exactly the window a post-settle render can target
  (the back-fill never reaches older, projection-only records). One pass
  newest-first; short-circuits at the first matching epoch."
  [frame-id render-key]
  (let [history (history-for frame-id)
        deps    (render-deps-for frame-id render-key)]
    (loop [i (dec (count history))]
      (when (>= i 0)
        (let [r (nth history i)]
          (if (epoch-value-changed-for-view? r render-key deps)
            (:epoch-id r)
            (recur (dec i))))))))

(defn resolve-render-epoch
  "Resolve the epoch a post-settle render of `render-key` should be
  attributed to in `frame-id` (rf2-vh1k3). Falls back to
  `default-epoch-id` (the rf2-qs6dl most-recently-settled epoch) when no
  better anchor is found, preserving the qs6dl behaviour for genuine
  reactive re-renders and for the degenerate no-render-key case.

  Resolution order:
    1. The newest epoch in which the view's OWN inputs changed
       (`value-changed-epoch-for`) — a genuine reactive re-render rides
       its causing cascade exactly as rf2-qs6dl intends.
    2. Otherwise the view's MOUNT epoch (`mount-epoch-for`) — a mount
       render, or a mount-burst tail that re-deref'd unchanged subs, is
       anchored to where the instance first rendered rather than leaking
       into the first post-mount cascade.
    3. Otherwise `default-epoch-id` — a brand-new instance whose very
       first render commits post-settle (no mount epoch recorded yet, no
       value-change scan hit): treat the settling cascade as its mount,
       which `record-mount-epoch!` then anchors."
  [frame-id render-key default-epoch-id]
  (or (when render-key (value-changed-epoch-for frame-id render-key))
      (when render-key (mount-epoch-for frame-id render-key))
      default-epoch-id))

(defn render-key-already-in-epoch?
  "True when `frame-id`'s epoch `epoch-id` record already carries a
  `:renders` row for `render-key` — used to de-dup a mount-burst tail
  that resolves back to the mount epoch where the render already landed
  (rf2-vh1k3). Reads the structured `:renders` projection (always
  present on a built record), not the optional `:trace-events`."
  [frame-id epoch-id render-key]
  (let [history (history-for frame-id)
        idx     (epoch-index history epoch-id)]
    (boolean
      (when idx
        (some (fn [row] (= render-key (:render-key row)))
              (:renders (nth history idx)))))))

;; ---- post-settle event back-fill (rf2-qs6dl render / rf2-wi900 sub-run) ----
;;
;; A `:view/render` (rf2-qs6dl) and a reactive `:sub/run` (rf2-wi900) both
;; fire at React COMMIT / DEREF time, which Reagent batches onto a later
;; tick AFTER the causing cascade settled. So each lands in the now-empty
;; capture buffer post-settle and — pre-fix — was harvested by the NEXT
;; cascade's settle, mis-attributing it to cascade N+1 (the one-epoch lag
;; both beads document: a phantom render in the wrong cascade's
;; `:renders`, a phantom sub-run + `:value-changed?` in the wrong
;; cascade's `:sub-runs` / Xray Views subs table).
;;
;; The fix back-fills the event into the cascade that CAUSED it (the
;; frame's most-recently-settled epoch — the cascade that dirtied the
;; view's / reaction's inputs and scheduled the re-render / recompute),
;; reusing the `last-settled-epoch` map. The render and sub-run paths are
;; byte-for-byte identical except for the structured projection slot they
;; touch (`:renders` vs `:sub-runs`), so one private `back-fill-event!`
;; does the ring mutation and the two public fns are thin wrappers
;; pinning the slot.

(defn- back-fill-event!
  "Append `event` and its projected `row` (into the `slot` projection)
  on the already-committed epoch record identified by `frame-id` +
  `epoch-id`. Returns the updated record (so the caller can re-notify
  listeners), or nil when the target epoch is no longer in the ring
  (evicted, or the event fired before any cascade settled).

  `row` is the structured projection entry, or nil — a render op that
  carries no `:renders` row (`:rf.view/rendered`) or a `:sub/skip` that
  carries no `:sub-runs` row rides only the `:trace-events` slot, exactly
  as `capture/project-all` projects no structured row for it. The
  mutation rewrites the matching record in the frame's history vector
  under a single `swap!`; the record stays at its original ring position
  so epoch ordering is preserved.

    * `:trace-events` is back-filled only when the record retained its raw
      trace stream (within `:trace-events-keep`) so raw-trace consumers
      (the Reactive panel's flow tally) see the post-settle event in the
      right cascade.
    * the structured `slot` projection — the primary consumer surface
      (Xray Views / Reactive panel) — is always present on a built
      record; the projected row is appended when non-nil."
  [frame-id epoch-id slot event row]
  (let [updated (atom nil)]
    (swap! histories update frame-id
           (fn [history]
             (let [history (or history [])
                   idx     (epoch-index history epoch-id)]
               (if (nil? idx)
                 history
                 (let [r  (nth history idx)
                       r' (cond-> r
                            (contains? r :trace-events)
                            (update :trace-events (fnil conj []) event)
                            (some? row)
                            (update slot (fnil conj []) row))]
                   (reset! updated r')
                   (assoc history idx r'))))))
    @updated))

(defn back-fill-render!
  "Back-fill `render-event` and its projected `:renders` `render-row`
  into the already-committed epoch `epoch-id` for `frame-id` (rf2-qs6dl).
  Thin wrapper over `back-fill-event!` pinning the `:renders` slot."
  [frame-id epoch-id render-event render-row]
  (back-fill-event! frame-id epoch-id :renders render-event render-row))

(defn back-fill-sub-run!
  "Back-fill `sub-event` and its projected `:sub-runs` `sub-run-row` into
  the already-committed epoch `epoch-id` for `frame-id` (rf2-wi900). Thin
  wrapper over `back-fill-event!` pinning the `:sub-runs` slot. Symmetric
  with `back-fill-render!`."
  [frame-id epoch-id sub-event sub-run-row]
  (back-fill-event! frame-id epoch-id :sub-runs sub-event sub-run-row))

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
  (let [b (get @capture-buffers frame-id [])]
    (swap! capture-buffers dissoc frame-id)
    b))

(defn- settling-dispatch-id
  "The `:dispatch-id` of the event being settled, read off the FIRST
  `:rf.event/run-start` emit in the buffer. nil when no run-start fired (a
  rejected / aborted dispatch, or a halt path whose event never ran)."
  [events]
  (some (fn [ev]
          (when (= :rf.event/run-start (:operation ev))
            (-> ev :tags :rf.trace/dispatch-id)))
        events))

(defn harvest-buffer-for-event!
  "Per rf2-nj6p7 — per-event harvest. Atomically read the frame's in-flight
  buffer and split it by the settling event's `:dispatch-id`:

    * RETURN the events that belong to the settling event — those whose
      `:tags :dispatch-id` matches the buffer's first `:event/run-start`
      id. Per rf2-avvwm an out-of-cascade ORPHAN (no `:dispatch-id` — e.g.
      a `:frame/created` emit fired between the last settled event and the
      next dequeue) is NOT folded in: Spec 009 §Dispatch correlation keeps
      an emit outside any cascade uncorrelated (neither a new epoch nor part
      of another epoch's record). Orphans are dropped upstream at the capture
      seam (`re-frame.epoch.capture/capture-event!`) so they never reach this
      buffer; the `did = sid` predicate below is the matching guard.
    * LEAVE in the buffer the events that belong to a DIFFERENT dequeued
      event — a child's `:event/dispatched` marker fires during the
      PARENT's do-fx (so it lands in the parent's window) but carries the
      CHILD's `:dispatch-id`; under per-event epochs it must ride the
      child's epoch, not the parent's (Spec 009 §Dispatch correlation:
      one `:dispatch-id` = one epoch). It stays buffered for the child's
      own `harvest-buffer-for-event!` at the child's settle.

  Falls back to a full read-and-clear when the buffer carries no
  `:event/run-start` (a rejected / aborted dispatch) — there is no
  settling id to scope by, and `settle!`'s empty-buffer / no-trigger
  policy handles the degenerate record."
  [frame-id]
  (let [b (get @capture-buffers frame-id [])]
    (if-let [sid (settling-dispatch-id b)]
      ;; Three-way partition by the event's :rf.trace/dispatch-id:
      ;;   * MINE   (= did sid)        — the settling event's own traces;
      ;;                                  returned to ride this epoch.
      ;;   * THEIRS (non-nil, ≠ sid)   — a child's `:event/dispatched`
      ;;                                  marker fired during THIS event's
      ;;                                  do-fx carrying the CHILD's id;
      ;;                                  LEFT in the buffer for the child's
      ;;                                  own settle (Spec 009 §Dispatch
      ;;                                  correlation: one dispatch-id = one
      ;;                                  epoch).
      ;;   * ORPHAN (nil did)          — an out-of-cascade emit (e.g.
      ;;                                  `:frame/created`) with no cascade
      ;;                                  to ride. DISCARDED here.
      ;;
      ;; Per rf2-avvwm orphans are dropped at the capture seam
      ;; (capture/capture-event! out-of-cascade branch) so in normal
      ;; operation none arrive. This seam is the defensive backstop: a
      ;; nil-did orphan that slips past the upstream guard has no settle
      ;; event to ever reclaim it, so retaining it (the pre-rf2-ee38b
      ;; behaviour) left it in the buffer indefinitely — re-grouped into
      ;; `theirs` and re-retained on every subsequent harvest. Discarding
      ;; it makes the harvest self-cleaning and removes reliance on the
      ;; upstream guard being perfect (rf2-ee38b §correctness). The
      ;; `did = sid` predicate already guarantees an orphan never folds
      ;; into an epoch's `:trace-events`; this only changes whether it
      ;; lingers in the buffer (no) vs. is dropped (yes).
      (let [mine   (filterv (fn [ev] (= (-> ev :tags :rf.trace/dispatch-id) sid)) b)
            theirs (filterv (fn [ev]
                              (let [did (-> ev :tags :rf.trace/dispatch-id)]
                                (and (some? did) (not= did sid))))
                            b)]
        ;; Leave the other-event traces (non-nil, non-matching id) in the
        ;; buffer for their own event's settle; drop orphans + ours.
        (if (seq theirs)
          (swap! capture-buffers assoc frame-id theirs)
          (swap! capture-buffers dissoc frame-id))
        mine)
      ;; No run-start — rejected/aborted dispatch. Clear and return all.
      (do (swap! capture-buffers dissoc frame-id)
          b))))

(defn drop-frame-buffer!
  "Drop the frame's in-flight capture buffer."
  [frame-id]
  (swap! capture-buffers dissoc frame-id)
  nil)

(defn reset-capture-buffers!
  "Wipe every in-flight capture buffer across all frames. Test fixtures
  use this in lockstep with `reset-histories!`.

  Per rf2-v0jwt: fixtures that sequence runs need a fresh capture
  state per fixture; a stale buffer from a previous fixture would
  otherwise be harvested into the next fixture's first cascade."
  []
  (reset! capture-buffers {})
  nil)

(defn reset-histories!
  "Wipe every frame's recorded epochs. Also clears the last-settled-epoch
  map (rf2-qs6dl) and the mount-attribution map (rf2-vh1k3, rf2-dq2b7)
  so a fixture's first cascade can't back-fill a render into a previous
  fixture's record nor inherit a stale render-key → {mount-epoch +
  read-set} anchor."
  []
  (reset! histories {})
  (reset-last-settled-epochs!)
  (reset-mount-attribution!)
  nil)

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))

;; Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656):
;; track which frames each cb has been delivered records for. When a
;; frame is destroyed, every cb whose observed-frames set contains
;; that frame receives a one-shot :rf.epoch.cb/silenced-on-frame-destroy
;; trace. The frame is then dropped from the cb's entry so a
;; re-registration of a same-keyed frame (e.g. `reset-frame! :app/main`)
;; can re-arm the silencing trace for a future destroy.
(defonce ^:private observed-frames-by-cb
  ;; cb-id → #{frame-id ...}
  (atom {}))

(defn put-listener!
  "Install or replace `f` under `id`. Also clears `id`'s
  observed-frames set so the new callback's silencing trace fires
  fresh against frames the new callback observes."
  [id f]
  (swap! listeners assoc id f)
  (swap! observed-frames-by-cb dissoc id)
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
  "Return the current `{cb-id → f}` map. Callers iterate this snapshot
  for fan-out — taking the snapshot once isolates iteration from
  concurrent `put-listener!` / `drop-listener!` updates."
  []
  @listeners)

(defn observations-snapshot
  "Return the current `{cb-id → #{frame-id ...}}` map."
  []
  @observed-frames-by-cb)

(defn record-observation!
  "Mark that the cb registered under `cb-id` has seen a record from
  `frame-id`. Guards against re-firing the atom watcher on the
  no-op case (cb already observes that frame) — for the common
  long-lived listener observing the same frame on every cascade,
  this is a single deref + membership check with no swap."
  [cb-id frame-id]
  (when frame-id
    (let [current @observed-frames-by-cb]
      (when-not (contains? (get current cb-id) frame-id)
        (swap! observed-frames-by-cb
               (fn [m]
                 (if (contains? (get m cb-id) frame-id)
                   m
                   (update m cb-id (fnil conj #{}) frame-id))))))))

(defn drop-frame-observation!
  "Drop `frame-id` from every cb's observed-frames set. cbs whose set
  goes empty as a result are dropped from the map entirely so the
  map doesn't accrete keys to empty sets."
  [frame-id]
  (swap! observed-frames-by-cb
         (fn [m]
           (reduce-kv (fn [acc cb-id frames]
                        (let [frames' (disj frames frame-id)]
                          (if (empty? frames')
                            (dissoc acc cb-id)
                            (assoc acc cb-id frames'))))
                      {}
                      m)))
  nil)
