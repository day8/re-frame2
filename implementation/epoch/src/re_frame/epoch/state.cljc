(ns re-frame.epoch.state
  "Shared state for the epoch surface — the six defonce atoms plus the
  low-level CRUD against them, and the config knob accessors. Per
  rf2-0wi86 (cohesion split): every other seam (`capture`, `assembly`,
  `write`, `listeners`) and the `re-frame.epoch` facade reach the
  shared atoms through this namespace; the atoms themselves stay
  `^:private` so cross-seam access is exclusively through the named
  helpers below.

  The atoms in question:

    config                  per-frame ring-buffer config + redact-fn
    histories               frame-id → vector<:rf/epoch-record>
    capture-buffers         frame-id → vector<trace-event> (in-flight)
    listeners               cb-id    → fn
    observed-frames-by-cb   cb-id    → #{frame-id ...}
    epoch-counter           monotonically-increasing :epoch-id source

  Per Phase-1 finding (rf2-0wi86): no two atoms are ever held in a
  single critical section, so a tiny state ns owns all of them with
  zero locking/ordering subtleties — the cross-cutting coupling is
  cosmetic, not structural.")

;; ---- configuration --------------------------------------------------------

(def ^:private default-depth
  ;; Deep enough to hold a typical debug session's cascade history;
  ;; trades bounded heap for stable time-travel coverage.
  50)

(def ^:private default-trace-events-keep
  ;; Per rf2-mrsck and Security.md §Epoch privacy posture: a finite
  ;; default that bounds dev-session heap growth from accumulated raw
  ;; cascade traces. The most-recent N records keep `:trace-events`;
  ;; older records keep only the cheap structured projections
  ;; (`:sub-runs` / `:renders` / `:effects`). Five matches the pair-
  ;; tool / Causa "what just happened?" working set — devs typically
  ;; care about the latest handful of cascades' raw streams; a deeper
  ;; ring depth is for time-travel reproducibility (`:db-after` is
  ;; cheap), not raw-trace inspection. Apps that genuinely need the
  ;; whole ring's traces can opt back in via
  ;; `(rf/configure :epoch-history {:trace-events-keep nil})` (or any
  ;; value >= the depth cap). Setting the slot to `0` drops every
  ;; record's `:trace-events`.
  5)

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

(defonce ^:private mount-epoch-by-render-key
  ;; frame-id → {render-key → epoch-id} : the epoch a render-key was
  ;; FIRST attributed to (its mount epoch). Bounds: one entry per live
  ;; render-key per frame; pruned with the frame on teardown.
  (atom {}))

;; frame-id → {render-key → #{sub-id ...}} : which subscriptions each
;; view reads. Learned from the `:reader-render-key` stamp on `:sub/run`
;; traces — which the runtime only sets when the reaction recomputes
;; SYNCHRONOUSLY inside the view's render (the mount / first-paint
;; deref). A post-settle reactive recompute fires during Reagent's
;; reaction-flush phase with no `*render-key*` bound, so the stamp is
;; absent there; the mount-time learning is sufficient because a view's
;; sub set is stable across its life. The index lets the render
;; back-fill (`value-changed-epoch-for`) decide whether a cascade
;; actually re-rendered a view by checking value-change on the view's
;; OWN subs, even when the post-settle sub-run carries no render-key.
(defonce ^:private render-deps-by-render-key (atom {}))

(defn record-render-deps!
  "Union `sub-id` into `render-key`'s read-set for `frame-id`. Called for
  every `:sub/run` that arrives stamped with a `:reader-render-key`
  (rf2-vh1k3) — the synchronous in-render deref that names the reading
  view. Idempotent; the set only grows."
  [frame-id render-key sub-id]
  (when (and frame-id render-key sub-id)
    (swap! render-deps-by-render-key
           update-in [frame-id render-key] (fnil conj #{}) sub-id))
  nil)

(defn render-deps-for
  "Return the set of sub-ids `render-key` is known to read in `frame-id`,
  or nil when none learned yet."
  [frame-id render-key]
  (get-in @render-deps-by-render-key [frame-id render-key]))

(defn drop-frame-render-deps!
  "Forget every render-key's read-set for `frame-id` (frame teardown)."
  [frame-id]
  (swap! render-deps-by-render-key dissoc frame-id)
  nil)

(defn reset-render-deps!
  "Wipe the render-deps map across all frames (fixture reset)."
  []
  (reset! render-deps-by-render-key {})
  nil)

(defn mount-epoch-for
  "Return the epoch-id `render-key` was first attributed to in `frame-id`,
  or nil when never seen."
  [frame-id render-key]
  (get-in @mount-epoch-by-render-key [frame-id render-key]))

(defn record-mount-epoch!
  "Record `epoch-id` as `render-key`'s mount epoch for `frame-id`, but
  only on the FIRST sighting — later attributions never overwrite the
  mount epoch (a re-render of an existing instance must not move its
  mount anchor)."
  [frame-id render-key epoch-id]
  (when (and frame-id render-key epoch-id)
    (swap! mount-epoch-by-render-key
           (fn [m]
             (if (get-in m [frame-id render-key])
               m
               (assoc-in m [frame-id render-key] epoch-id)))))
  nil)

(defn drop-frame-mount-epochs!
  "Forget every render-key's mount epoch for `frame-id` (frame teardown)."
  [frame-id]
  (swap! mount-epoch-by-render-key dissoc frame-id)
  nil)

(defn reset-mount-epochs!
  "Wipe the mount-epoch map across all frames (fixture reset)."
  []
  (reset! mount-epoch-by-render-key {})
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
          (and (= :sub/run (:operation ev))
               (true? (-> ev :tags :value-changed?))
               (let [t (:tags ev)]
                 (or (= render-key (:reader-render-key t))
                     (and deps (contains? deps (:sub-id t)))))))
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
  (let [history (history-for frame-id)]
    (boolean
      (some (fn [r]
              (and (= epoch-id (:epoch-id r))
                   (some (fn [row] (= render-key (:render-key row)))
                         (:renders r))))
            history))))

(defn back-fill-render!
  "Append `render-event` and its projected `:renders` row into the
  already-committed epoch record identified by `frame-id` + `epoch-id`
  (rf2-qs6dl). Returns the updated record (so the caller can re-notify
  listeners), or nil when the target epoch is no longer in the ring
  (evicted, or the render fired before any cascade settled).

  `render-row` is the structured `:renders` entry (or nil — a non-
  `:view/render` render op such as `:rf.view/rendered` rides only the
  `:trace-events` slot). The mutation rewrites the matching record in
  the frame's history vector under a single `swap!`; the record stays at
  its original ring position so epoch ordering is preserved."
  [frame-id epoch-id render-event render-row]
  (let [updated (atom nil)]
    (swap! histories update frame-id
           (fn [history]
             (let [history (or history [])
                   idx     (some (fn [i]
                                   (when (= epoch-id (:epoch-id (nth history i)))
                                     i))
                                 (range (count history)))]
               (if (nil? idx)
                 history
                 (let [r  (nth history idx)
                       r' (cond-> r
                            ;; Only records that retained their raw
                            ;; trace stream (within :trace-events-keep)
                            ;; carry the slot; back-fill it when present
                            ;; so the Reactive panel's flow tally and any
                            ;; raw-trace consumer see the post-settle
                            ;; render in the right cascade.
                            (contains? r :trace-events)
                            (update :trace-events (fnil conj []) render-event)
                            ;; The structured :renders projection is the
                            ;; primary consumer surface (Causa Views /
                            ;; Reactive panel). Always present on a built
                            ;; record; append the projected row.
                            (some? render-row)
                            (update :renders (fnil conj []) render-row))]
                   (reset! updated r')
                   (assoc history idx r'))))))
    @updated))

;; ---- post-settle sub-run back-fill (rf2-wi900) ----------------------------
;;
;; The subs sibling of the render back-fill above. A `:sub/run` (or
;; `:sub/skip`) trace fires when a reaction recomputes — and reactions
;; recompute LAZILY at React render (deref) time, which Reagent batches
;; onto a later tick AFTER the causing cascade settled. So a sub-run, like
;; a render, lands in the now-empty buffer post-settle and — pre-rf2-wi900
;; — was harvested by the NEXT cascade's settle, mis-attributing every
;; reactive recompute (and its `:value-changed?` / `:prev-value` / `:value`
;; attribution) to cascade N+1 (the one-epoch lag the bead documents,
;; visibly wrong in Causa's per-cascade Views subs table).
;;
;; The fix mirrors the render path exactly: a sub-run that fires with no
;; in-flight cascade for the frame is back-filled into that frame's
;; most-recently-settled epoch record (the cascade that dirtied the
;; reaction's inputs and scheduled the recompute), reusing the same
;; `last-settled-epoch` map the render back-fill tracks.

(defn back-fill-sub-run!
  "Append `sub-event` and its projected `:sub-runs` row into the
  already-committed epoch record identified by `frame-id` + `epoch-id`
  (rf2-wi900). Returns the updated record (so the caller can re-notify
  listeners), or nil when the target epoch is no longer in the ring
  (evicted, or the sub-run fired before any cascade settled).

  `sub-run-row` is the structured `:sub-runs` entry (or nil — a `:sub/skip`
  op carries no `:sub-runs` row, exactly as `project-all` projects no
  `:sub-runs` row for it; it rides only the `:trace-events` slot). The
  mutation rewrites the matching record in the frame's history vector
  under a single `swap!`; the record stays at its original ring position
  so epoch ordering is preserved. Symmetric with `back-fill-render!`."
  [frame-id epoch-id sub-event sub-run-row]
  (let [updated (atom nil)]
    (swap! histories update frame-id
           (fn [history]
             (let [history (or history [])
                   idx     (some (fn [i]
                                   (when (= epoch-id (:epoch-id (nth history i)))
                                     i))
                                 (range (count history)))]
               (if (nil? idx)
                 history
                 (let [r  (nth history idx)
                       r' (cond-> r
                            ;; Only records that retained their raw trace
                            ;; stream (within :trace-events-keep) carry the
                            ;; slot; back-fill it when present so raw-trace
                            ;; consumers see the post-settle sub-run in the
                            ;; right cascade.
                            (contains? r :trace-events)
                            (update :trace-events (fnil conj []) sub-event)
                            ;; The structured :sub-runs projection is the
                            ;; primary consumer surface (Causa Views subs
                            ;; table). Always present on a built record;
                            ;; append the projected row.
                            (some? sub-run-row)
                            (update :sub-runs (fnil conj []) sub-run-row))]
                   (reset! updated r')
                   (assoc history idx r'))))))
    @updated))

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
  `:event/run-start` emit in the buffer. nil when no run-start fired (a
  rejected / aborted dispatch, or a halt path whose event never ran)."
  [events]
  (some (fn [ev]
          (when (and (= :event (:op-type ev))
                     (= :event (:operation ev))
                     (= :run-start (-> ev :tags :phase)))
            (-> ev :tags :dispatch-id)))
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
      (let [{mine true theirs false}
            (group-by (fn [ev]
                        ;; Per rf2-avvwm: ONLY the settling event's own
                        ;; traces (matching :dispatch-id) ride this epoch.
                        ;; A nil-:dispatch-id orphan (out-of-cascade emit
                        ;; such as :frame/created) is no longer folded in —
                        ;; orphans are dropped at the capture seam
                        ;; (capture/capture-event! out-of-cascade branch) so
                        ;; they never reach this buffer; this predicate is
                        ;; the matching guard (any nil-did event that did
                        ;; slip through is LEFT in the buffer, not vacuumed
                        ;; into the settling epoch). Pre-fix `(or (nil? did)
                        ;; (= did sid))` swept an orphan in as the cascade's
                        ;; first :trace-events entry — the regression closed.
                        (= (-> ev :tags :dispatch-id) sid))
                      b)]
        ;; Leave the other-event traces (non-nil, non-matching id) in the
        ;; buffer for their own event's settle; take ours.
        (if (seq theirs)
          (swap! capture-buffers assoc frame-id (vec theirs))
          (swap! capture-buffers dissoc frame-id))
        (vec mine))
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
  map (rf2-qs6dl) and the mount-epoch map (rf2-vh1k3) so a fixture's
  first cascade can't back-fill a render into a previous fixture's
  record nor inherit a stale render-key→mount-epoch anchor."
  []
  (reset! histories {})
  (reset-last-settled-epochs!)
  (reset-mount-epochs!)
  (reset-render-deps!)
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
