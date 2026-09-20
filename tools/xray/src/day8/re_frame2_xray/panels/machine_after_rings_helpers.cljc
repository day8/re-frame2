(ns day8.re-frame2-xray.panels.machine-after-rings-helpers
  "Pure-data helpers for the Machine Inspector `:after` timer countdown
  rings (rf2-7hwwe).

  ## What this owns

  Translates Xray's trace ring buffer into a vector of *armed* `:after`
  timer records the chart can overlay as countdown rings around state
  nodes — the wall-clock-time visualisation arc per
  `spec/019-Cross-Cutting-Insight.md`.

  Each timer record:

      {:machine-id  <kw>      ;; the live actor the timer belongs to (read from
                              ;; the trace's :actor-id; field name kept for callers)
       :state       <kw|vec>  ;; the bearing state-node
       :armed-at    <int>     ;; trace event time (ms epoch)
       :fires-at    <int>     ;; armed-at + duration-ms
       :duration-ms <int>     ;; resolved delay
       :epoch       <int>     ;; the machine's :after-epoch when scheduled
       :status      <kw>      ;; :armed | :fired | :stale | :cancelled | :skipped
       :delay-key   <any>     ;; the original :after map key (literal/sub-vec/fn)
       :sub-id      <kw|nil>} ;; subscription id when :delay-source = :sub
                              ;; (read from the trace's canonical :rf.sub/id; rf2-1b6uh5)

  ## Source events (per Spec 005 §Delayed :after transitions)

  The runtime emits these on the trace bus:

    - `:rf.machine.timer/scheduled`             — arms a timer
    - `:rf.machine.timer/fired` (`:fired? true`)— normal expiry
    - `:rf.machine.timer/fired` (`:fired? false`)— guard-suppressed
    - `:rf.machine.timer/stale-after`           — epoch mismatch
    - `:rf.machine.timer/cancelled`             — explicit cancel
      (every cancellation path — state exit / machine destroy / sub-
      vec re-resolution / supersede / frame destroy — per rf2-82a0u;
      `:reason` discriminates)
    - `:rf.machine.timer/skipped-on-server`     — SSR no-op

  ## Folding algorithm

  Walk the trace buffer oldest-first. Each `scheduled` event opens a
  timer keyed by `(machine-id, state, epoch, delay)`; a later `fired`
  / `stale-after` / `cancelled` event for the same key closes it. The
  `delay` discriminator (rf2-2es2x8) matters because a single state's
  `:after` map can schedule several timers CONCURRENTLY at the same
  `(machine-id, state, epoch)` — one entry per delay — so `delay` is
  required to keep them as independent records / independent rings.
  The latest event wins so re-schedules at the same key replace the
  prior record (matches the runtime's idempotent
  `cancel-after-timer-entry!` shape).

  ## Ring geometry

  Live mode: `(ring-fraction timer now-ms) → [0.0 .. 1.0]`
  - 1.0 = just armed
  - 0.0 = about to fire
  - <0.0 = past deadline (delayed firing, clamped to 0)

  Retrospective: caller passes the scrubber's anchor-time as `now-ms`;
  the ring freezes at the position it occupied at that instant.

  ## Colours

  Map the fraction to a semantic colour tier:

    - 0.66..1.0  → :green   (fresh)
    - 0.33..0.66 → :amber   (mid-cycle)
    - 0.0..0.33  → :red     (firing-soon / past-deadline)

  Final states (`:fired`, `:stale`, `:skipped`) → :gray.
  `:cancelled` → :gray with a fade + diagonal cross drawn by the view.

  ## Why a separate .cljc

  Same dual-target pattern every panel helper uses (the JVM test
  target drives the algebra without a CLJS runtime). The CLJS-only
  surfaces (raf tick driver, dispatch) live in
  `machine_after_rings.cljs`."
  ;; No requires — pure data → data. (`clojure.string` sat here unused;
  ;; clj-kondo had been warning on it. rf2-y8doi.23.)
  )

;; ---- canonical operation taxonomy ---------------------------------------

(def timer-operations
  "Trace operations the rings helper consumes. Defined as a set so
  `transition-event?`-style predicates can compose without repeating
  the literal-keyword list at every call site."
  #{:rf.machine.timer/scheduled
    :rf.machine.timer/fired
    :rf.machine.timer/stale-after
    :rf.machine.timer/cancelled
    :rf.machine.timer/skipped-on-server})

(defn timer-event?
  "True iff `ev` carries one of the recognised timer operations."
  [ev]
  (and (map? ev)
       (contains? timer-operations (:operation ev))))

(defn- machine-id-of
  "Per Spec 009 every `:rf.machine.timer/*` event stamps the timer's owning
  actor INSTANCE under `:actor-id` (rf2-ws5thu / rf2-yyvtk5 — including the
  `:fired` / `:stale-after` rows, which now carry it too); `:machine-id` is
  reserved for the registered TYPE. Prefer `:actor-id`, then fall back to
  `:machine-id` / `:handler-id` for legacy fixtures.

  Returns nil when no id can be resolved — the caller filters those out
  before folding."
  [ev]
  (or (get-in ev [:tags :actor-id])
      (get-in ev [:tags :machine-id])
      (get-in ev [:tags :handler-id])))

;; ---- timer key resolution -----------------------------------------------

(defn- timer-key
  "Composite key for the timer-table fold. `(machine-id, state, epoch,
  delay)` is the runtime's identity tuple.

  rf2-2es2x8 — `epoch` alone does NOT disambiguate: `build-after-fx`
  computes ONE epoch per scheduling node and reuses it across EVERY
  entry in that node's `:after` map (Spec 005 §Multiple :after per
  state — `{:after {5000 :warn 30000 :timeout}}` schedules both timers
  concurrently at the same `(machine-id, state, epoch)`). Every
  `:rf.machine.timer/*` trace (scheduled / fired / stale-after /
  cancelled / skipped-on-server) carries `:delay` as the resolved ms
  value, stable across a timer's whole scheduled→fired/cancelled
  lifecycle, so it is included here as the discriminator — each
  concurrent `:after` timer on a state gets its own key, its own fold
  record, and its own ring. Re-scheduling at the same state still
  bumps the epoch (per `re-frame.machines.transition/pick-after-
  transition`), so a fresh epoch ALWAYS starts a new record regardless
  of delay.

  Per rf2-82a0u the unified `:rf.machine.timer/cancelled` event
  carries `:epoch` (mirroring the `:scheduled` shape), so the closing
  match is exact for cancel rows — the `:*` fallback is retained for
  pre-rf2-82a0u trace replays + edge cases where the closing event's
  tags are projected through a lossy intermediary."
  [machine-id state epoch delay]
  {:machine-id machine-id
   :state      state
   :epoch      (or epoch :*)
   :delay      (or delay :*)})

;; ---- public: project active timers --------------------------------------

(defn- update-or-cancel
  "Match a closing event against the open timer-table. When `epoch`
  AND `delay` are both known, exact match on the full
  `(machine-id, state, epoch, delay)` key; otherwise fall back to the
  most recent open record for `(machine-id, state)` — narrowed to
  `delay` when the closing event carries one, so two concurrent
  `:after` timers on the same state/epoch (rf2-2es2x8) don't collide
  in the fallback path either. Returns `[k record-or-nil]` — `nil`
  record means no open entry to update.

  Pure fn — JVM-runnable."
  [open machine-id state epoch delay]
  (let [exact-k (timer-key machine-id state epoch delay)
        exact   (get open exact-k)]
    (if exact
      [exact-k exact]
      ;; Fall back to the latest open record for the (machine-id, state)
      ;; pair — the closing event didn't stamp epoch (and/or delay) so
      ;; we trust the ordering of the buffer, narrowed by `:delay` when
      ;; available so concurrent timers at the same state don't collide.
      (let [matches (filter (fn [[k _v]]
                              (and (= machine-id (:machine-id k))
                                   (= state      (:state k))
                                   (or (nil? delay) (= delay (:delay k)))))
                            open)]
        (if (seq matches)
          ;; Pick the most-recently-armed one. The open record's
          ;; `:armed-at` is monotonic per `(machine-id, state)`.
          (let [[k v] (apply max-key (fn [[_ v]] (or (:armed-at v) 0))
                             matches)]
            [k v])
          [exact-k nil])))))

(defn fold-timer-events
  "Reduce a sequence of timer events (oldest-first) into the final
  timer table — `{<k> <record>}`. The table tracks every timer the
  buffer has seen, with each record stamped with its current
  `:status`. Pure fn — JVM-runnable.

  The fold guarantees:

    - Every `:scheduled` opens / refreshes a record at its `(machine-
      id, state, epoch)` key with `:status :armed`.
    - A `:fired` event flips the matching record's `:status` to
      `:fired` (or `:guard-suppressed` when `:fired? false`).
    - A `:stale-after` flips it to `:stale`.
    - A `:cancelled` flips it to `:cancelled` and retains the
      `:armed-at` / `:fires-at` so the view can render the ring at
      its last position with the crossed-out overlay. Per rf2-82a0u
      the closing event carries `:reason` (closed set: `:on-exit /
      :on-destroy / :on-resolution / :on-supersede / :on-frame-
      destroy`); the rings panel doesn't currently branch on
      `:reason` but a future visualiser can.
    - `:skipped-on-server` (`:platform :server`) flips to `:skipped`.

  The caller filters down to the entries it wants to render (typically
  `:armed` for live rings + recently-closed for fading-out states)."
  [events]
  (reduce
    (fn [open ev]
      (let [op         (:operation ev)
            tags       (get ev :tags {})
            machine-id (machine-id-of ev)
            state      (or (:state tags) (:to tags) (:to-state tags))
            epoch      (:epoch tags)
            t          (:time ev)
            delay      (:delay tags)
            delay-src  (:delay-source tags)
            delay-key  (:delay-key tags)
            ;; rf2-1b6uh5 — the dynamic-delay subscription identity now rides
            ;; under the canonical `:rf.sub/id` (`:sub-id` fallback for legacy
            ;; fixtures). The projection record keeps the internal `:sub-id`
            ;; field name (not a trace tag) for downstream readers.
            sub-id     (or (:rf.sub/id tags) (:sub-id tags))]
        (cond
          (nil? machine-id) open
          (nil? state)      open

          (= :rf.machine.timer/scheduled op)
          (assoc open (timer-key machine-id state epoch delay)
                 {:machine-id  machine-id
                  :state       state
                  :armed-at    t
                  :fires-at    (when (and t (number? delay)) (+ t delay))
                  :duration-ms (when (number? delay) delay)
                  :epoch       epoch
                  :status      :armed
                  :delay-source delay-src
                  :delay-key   delay-key
                  :sub-id      sub-id})

          (= :rf.machine.timer/fired op)
          (let [[k v] (update-or-cancel open machine-id state epoch delay)]
            (if v
              (assoc open k
                     (assoc v
                            :status (if (false? (:fired? tags))
                                      :guard-suppressed
                                      :fired)
                            :closed-at t))
              open))

          (= :rf.machine.timer/stale-after op)
          (let [;; stale-after emits `:scheduled-epoch` (the timer's epoch)
                ;; + `:current-epoch` (the machine's epoch when the timer
                ;; fired) — we close the scheduled-epoch record.
                stale-epoch (or (:scheduled-epoch tags) epoch)
                [k v] (update-or-cancel open machine-id state stale-epoch delay)]
            (if v
              (assoc open k (assoc v :status :stale :closed-at t))
              open))

          (= :rf.machine.timer/cancelled op)
          (let [[k v] (update-or-cancel open machine-id state epoch delay)]
            (if v
              (assoc open k (assoc v
                                   :status :cancelled
                                   :closed-at t
                                   ;; Per rf2-82a0u: carry the `:reason`
                                   ;; closed-set tag onto the record so
                                   ;; downstream consumers can branch on
                                   ;; cause (state-exit vs destroy vs
                                   ;; sub re-resolve etc) without
                                   ;; re-reading the trace event.
                                   :cancel-reason (:reason tags)))
              open))

          (= :rf.machine.timer/skipped-on-server op)
          (assoc open (timer-key machine-id state epoch delay)
                 {:machine-id  machine-id
                  :state       state
                  :armed-at    t
                  :fires-at    nil
                  :duration-ms (when (number? delay) delay)
                  :epoch       epoch
                  :status      :skipped
                  :delay-source delay-src
                  :delay-key   delay-key
                  :sub-id      sub-id})

          :else open)))
    {}
    (or events [])))

(defn project-timers
  "Project Xray's trace buffer into a flat vector of timer records
  for `machine-id`. Sorted oldest-first by `:armed-at` for stable
  hover-region ordering.

  `display-frame` (rf2-y8doi.23; named `target-frame` until rf2-a28eo)
  is the frame whose instance is ON SCREEN. A machine DEFINITION can be
  registered once and instantiated in several frames — a testbed
  mounting two hosts side by side is the ordinary case — and a singleton
  actor-id is identical across them, so folding on
  `(machine-id, state, epoch, delay)` alone let two frames' timers
  collide on one ring key: a `:cancelled` in frame A closed the
  `:armed` record frame B had just opened. Every `:rf.machine.timer/*`
  trace stamps its owning frame under `:tags :frame` (measured at the
  producer for all five ops — `scheduled` / `fired` / `stale-after` /
  `cancelled` / `skipped-on-server`; re-measured under rf2-a28eo across
  all SEVEN emit sites, `/scheduled` and `/fired` having two each), so
  narrowing the buffer BEFORE the fold keeps each frame's timers in
  their own fold.

  IT IS NOT `:rf.xray/target-frame`, AND THE DIFFERENCE IS THE WHOLE OF
  rf2-a28eo. That slot is the COLLECTOR's target — which host frame the
  operator asked to observe — and it DEFAULTS TO NIL (UNSELECTED,
  EP-0002). A caller handing it here raw therefore left this narrowing
  off in the very posture the panel opens in, and the two frames folded
  back together. The display scope is resolved from the focused
  transition record's own `:frame-id` instead, with the collector target
  as the fallback; `:rf.xray/active-timers-for-focused-machine` in
  `machine_after_rings.cljs` is where that happens.

  `display-frame` nil means NOTHING COULD BE RESOLVED, and applies NO
  filter — there is nothing to disambiguate against, and dropping every
  event would blank the rings. That branch is deliberate and
  load-bearing rather than a gap: a legacy replay whose traces pre-date
  the `:frame` stamp lands on it, and so does an unstamped fixture.

  Returns `[]` when `machine-id` is nil. Pure fn — JVM-runnable."
  ([trace-buffer machine-id]
   (project-timers trace-buffer machine-id nil))
  ([trace-buffer machine-id display-frame]
   (if (nil? machine-id)
     []
     (let [events (->> (or trace-buffer [])
                       (filter timer-event?)
                       (filter (fn [ev] (= machine-id (machine-id-of ev))))
                       (filter (fn [ev]
                                 (or (nil? display-frame)
                                     (= display-frame
                                        (get-in ev [:tags :frame])))))
                       ;; Oldest first — the fold relies on chronological
                       ;; order so a later cancellation overrides an earlier
                       ;; arming.
                       (sort-by (fn [ev] (or (:id ev) (:time ev) 0))))
           table  (fold-timer-events events)]
       (vec
         (sort-by (fn [r] (or (:armed-at r) 0))
                  (vals table)))))))

(def cancelled-retention-ms
  "How long (ms) a `:cancelled` ring stays on the chart after its
  `:closed-at` before it is evicted (rf2-y8doi.23).

  A cancelled ring is a fade + diagonal cross — a MOMENTARY signal that
  an armed timer was torn down early. Before this it had no retention at
  all: `active-timers-for-machine` returned every `:cancelled` record the
  buffer had ever seen, for as long as the buffer held it, so a state the
  operator entered and left N times left N permanent grey crossed rings —
  and, because the machines-viz overlay keys a ring by its `:node-id`,
  all N sat under ONE React key."
  2000)

(defn cancelled-ring-live?
  "True while a `:cancelled` record is still inside its retention window
  at `now-ms` — i.e. while the crossed ring is still ON SCREEN.

  The SINGLE owner of that boundary (rf2-q9x6h). [[prune-timers]] evicts
  exactly when this goes false and [[needs-ticking?]] keeps the clock
  alive exactly while it holds, so \"the ring is visible\" and \"the clock
  still owes this ring a tick\" cannot drift apart. Before this they were
  two separate readings of the same window, and only one of them existed:
  the ring had a deadline and nothing kept a clock running to reach it.

  A record carrying NO `:closed-at` cannot be aged, so it is NOT live —
  [[prune-timers]] drops it rather than keep an unboundable ring, and a
  dropped ring needs no clock.

  `now-ms` nil means NO CLOCK: nothing can be aged, so the ring is still
  live and [[needs-ticking?]] asks for the clock that will age it. That
  matches [[prune-timers]]'s own nil semantics (it returns the vector
  unchanged), and it is self-correcting — the first tick supplies a real
  `now-ms`. Pure fn — JVM-runnable."
  [r now-ms]
  (and (= :cancelled (:status r))
       (some? (:closed-at r))
       (or (nil? now-ms)
           (<= (- now-ms (:closed-at r)) cancelled-retention-ms))))

(defn timers-for-machine
  "The BUFFER-KEYED half of the rings projection: the timer records the
  chart could render a ring for, given the trace buffer alone.

  Filters `project-timers` to:

    - `:armed`        — countdown is in progress.
    - `:cancelled`    — fade + diagonal cross, until [[prune-timers]]
                        evicts it.

  Closed-state timers (`:fired`, `:stale`, `:guard-suppressed`,
  `:skipped`) drop out — the ring's purpose is to show wall-clock-
  pressure on the chart; a fired ring is just chart noise.

  Split out from [[active-timers-for-machine]] by rf2-y8doi.23 so the
  sub that folds the whole trace buffer is keyed on the BUFFER and not
  on the clock. The rings sub used to take `:rf.xray/now-ms` as an
  input, so every rAF tick (~60 Hz while any timer is armed) re-folded
  the entire buffer to answer a question only the last step — the
  now-keyed filter below — actually needed. Pure fn — JVM-runnable."
  ([trace-buffer machine-id]
   (timers-for-machine trace-buffer machine-id nil))
  ([trace-buffer machine-id display-frame]
   (filterv (fn [r] (or (= :armed (:status r))
                        (= :cancelled (:status r))))
            (project-timers trace-buffer machine-id display-frame))))

(defn prune-timers
  "The NOW-KEYED half: drop the records that should not be on screen at
  wall-clock instant `now-ms`. Cheap — it walks the already-folded
  vector, never the buffer.

  Two evictions, and one dedupe:

    - **Zombie `:armed`** — `:fires-at` more than 5s in the past.
      Protects against a projection stranded `:armed` because its
      `:fired` trace was evicted from the ring buffer.
    - **Expired `:cancelled`** — `:closed-at` more than
      [[cancelled-retention-ms]] in the past. A record carrying NO
      `:closed-at` cannot be aged, so it is dropped rather than kept
      for ever — an unboundable ring is the defect this eviction
      exists to remove.
    - **Newest-wins per bearing state** for `:cancelled`. One node
      shows at most one crossed ring, so repeated entry/exit of a
      state cannot pile them up under the overlay's single
      `:node-id` React key. `:armed` records are deliberately NOT
      deduped: per rf2-2es2x8 a state's `:after` map may schedule
      several timers concurrently, and each is its own ring.

  `now-ms` nil means NO CLOCK — nothing can be aged, so the vector is
  returned unchanged. Pure fn — JVM-runnable."
  [timers now-ms]
  (let [timers (vec (or timers []))]
    (if (nil? now-ms)
      timers
      (let [zombie-threshold-ms 5000
            armed  (filterv (fn [r]
                              (and (= :armed (:status r))
                                   (not (and (:fires-at r)
                                             (> (- now-ms (:fires-at r))
                                                zombie-threshold-ms)))))
                            timers)
            newest (reduce
                     (fn [acc r]
                       (if (cancelled-ring-live? r now-ms)
                         (let [k   (:state r)
                               cur (get acc k)]
                           (if (or (nil? cur)
                                   (> (:closed-at r) (:closed-at cur)))
                             (assoc acc k r)
                             acc))
                         acc))
                     {}
                     timers)]
        (vec (sort-by (fn [r] (or (:armed-at r) 0))
                      (concat armed (vals newest))))))))

(defn active-timers-for-machine
  "Return the timer records the chart should render rings for at
  wall-clock instant `now-ms` — [[timers-for-machine]] composed with
  [[prune-timers]].

  `display-frame` narrows the buffer to the frame whose instance is ON
  SCREEN — NOT to `:rf.xray/target-frame`, which is the collector's
  target and a fallback only (rf2-a28eo); see [[project-timers]] for
  why, and for what nil means.

  Kept as one entry point because the JVM helper suite drives the whole
  pipeline through it; the production sub takes the two halves
  separately so the expensive one is not re-run per animation frame."
  ([trace-buffer machine-id]
   (active-timers-for-machine trace-buffer machine-id nil nil))
  ([trace-buffer machine-id now-ms]
   (active-timers-for-machine trace-buffer machine-id now-ms nil))
  ([trace-buffer machine-id now-ms display-frame]
   (prune-timers (timers-for-machine trace-buffer machine-id display-frame)
                 now-ms)))

;; ---- ring geometry ------------------------------------------------------

(defn ring-fraction
  "Compute the remaining-time fraction for `timer` at wall-clock
  instant `now-ms`. Returns a double in `[0.0, 1.0]`:

    - 1.0  = just armed (full ring)
    - 0.5  = halfway through the countdown
    - 0.0  = about to fire / past deadline
    - nil  = the timer has no resolvable duration (literal delay
             missing / unresolved sub) — caller renders without a ring
             progress arc.

  Pure fn — JVM-runnable."
  [{:keys [armed-at fires-at duration-ms]} now-ms]
  (cond
    (or (nil? armed-at) (nil? fires-at) (nil? duration-ms)
        (not (pos? duration-ms))
        (nil? now-ms))
    nil

    :else
    (let [remaining (- fires-at now-ms)
          frac      (/ (double remaining) (double duration-ms))]
      (cond
        (<= frac 0.0) 0.0
        (>= frac 1.0) 1.0
        :else         frac))))

(defn ring-color
  "Map a ring `fraction` to the design-token keyword the SVG primitive
  + view consume. Pure fn.

    - >= 0.66        → :green   (plenty of headroom)
    - 0.33..0.66     → :amber   (mid-cycle)
    - 0.0..0.33      → :red     (firing-soon / past-deadline)
    - past deadline  → :red     (we clamp `ring-fraction` at 0 already)
    - nil            → :gray    (no progress data — degenerate ring)"
  [fraction]
  (cond
    (nil? fraction)    :gray
    (>= fraction 0.66) :green
    (>= fraction 0.33) :amber
    :else              :red))

(defn timer-color
  "Resolve the colour for a `timer` record at instant `now-ms`. Combines
  `ring-fraction` + `ring-color` with status-based overrides:

    - `:cancelled` → `:gray` (the view also draws the diagonal cross)
    - `:fired` / `:stale` / `:guard-suppressed` / `:skipped` → `:gray`
    - `:armed` → fraction-driven (`ring-color`)

  Pure fn — JVM-runnable. Used by both the SVG renderer and the JVM
  test suite."
  [timer now-ms]
  (case (:status timer)
    :cancelled        :gray
    :fired            :gray
    :stale            :gray
    :guard-suppressed :gray
    :skipped          :gray
    :armed            (ring-color (ring-fraction timer now-ms))
    :gray))

(defn ms-remaining
  "Convenience for the hover tooltip. nil for non-armed timers and
  unresolved-duration timers."
  [{:keys [fires-at] :as _timer} now-ms]
  (when (and fires-at now-ms)
    (max 0 (- fires-at now-ms))))

(defn format-timer-tooltip
  "Human-readable tooltip for a ring hover. Pure fn — used by the
  `<title>` element in the SVG (zero-cost accessible tooltip).

    `:armed`        → 'state · 1234ms remaining · fires @5678'
    `:cancelled`    → 'state · cancelled · last @1234'
    `:fired`        → 'state · fired @1234'
    `:stale`        → 'state · stale (epoch mismatch)'
    `:skipped`      → 'state · skipped (server-side)'
    `:guard-suppressed` → 'state · fired but guard suppressed'"
  [{:keys [state status fires-at duration-ms closed-at] :as timer}
   now-ms]
  (let [state-s (if (keyword? state) (str state) (pr-str state))
        dur     (when duration-ms (str " (" duration-ms "ms)"))]
    (case status
      :armed
      (let [remaining (ms-remaining timer now-ms)
            fires-s   (when fires-at (str " · fires @" fires-at))]
        (str state-s
             (when remaining (str " · " remaining "ms remaining"))
             fires-s
             dur))

      :cancelled
      (str state-s " · cancelled"
           (when closed-at (str " @" closed-at))
           dur)

      :fired
      (str state-s " · fired"
           (when closed-at (str " @" closed-at))
           dur)

      :stale
      (str state-s " · stale (epoch mismatch)" dur)

      :skipped
      (str state-s " · skipped (server-side)" dur)

      :guard-suppressed
      (str state-s " · fired (guard suppressed)"
           (when closed-at (str " @" closed-at))
           dur)

      (str state-s " · " (when status (name status))))))

;; ---- xyflow overlay ring-specs (rf2-uv1on) -----------------------------

(defn timer->ring-spec
  "Project a single `timer` record into the presentation-ready ring
  spec the machines-viz xyflow `AfterRingsOverlay` consumes. The
  overlay walks the DOM to POSITION the ring (xyflow owns node
  positions post-migration); this helper supplies the colour /
  fraction / tooltip + the `:node-id` the overlay queries the DOM
  for. Pure fn — JVM-runnable.

  `id-fn` is `chart.layout/highlight-id` (passed in to keep this ns
  free of a compile-time dep on the chart layout). It resolves the
  timer's `:state` keyword / path to the string node-id xyflow stamps
  on the bearing node's `data-testid`.

  `now-ms` is the wall-clock instant the fraction freezes at — live
  mode passes the rAF-bumped now; retrospective mode passes the
  scrubber's anchor time, so the ring's swept arc reflects the
  scrubbed instant (scrubber-aware retro-replay).

  Returns nil when the state has no resolvable node-id."
  [timer id-fn now-ms]
  (when-let [nid (and id-fn (some-> (:state timer) id-fn))]
    (let [state-id (if (keyword? (:state timer))
                     (name (:state timer))
                     (pr-str (:state timer)))]
      {:node-id    nid
       :fraction   (ring-fraction timer now-ms)
       :color      (timer-color timer now-ms)
       :cancelled? (= :cancelled (:status timer))
       :tooltip    (format-timer-tooltip timer now-ms)
       :testid     (str "rf-xray-machine-inspector-after-ring-" state-id)
       ;; Carried through so the host's hover handler can key the
       ;; timer-hover slot by its identity tuple.
       :machine-id (:machine-id timer)
       :state      (:state timer)
       :epoch      (:epoch timer)})))

(defn timers->ring-specs
  "Map a vector of `timers` into machines-viz overlay ring-specs.
  Drops timers whose state has no resolvable node-id. Pure fn —
  JVM-runnable. Replaces the SVG-era `timers->ring-positions` (which
  resolved `{:cx :cy :r}` from a positioned graph); positioning now
  lives in the overlay's DOM walk, so this fn only carries the
  presentation payload + the `:node-id` the overlay queries."
  [timers id-fn now-ms]
  (vec
    (keep (fn [t] (timer->ring-spec t id-fn now-ms))
          (or timers []))))

;; ---- tick driver gating -------------------------------------------------

(defn needs-ticking?
  "True when the rings panel should run its rAF tick driver. False when:

    - NOTHING ON SCREEN HAS A DEADLINE: no `:armed` timer is counting
      down, and no `:cancelled` ring is still inside its retention
      window ([[cancelled-ring-live?]]).
    - The scrubber is NOT at `:present` (retrospective mode freezes
      every ring at the scrubber's anchor time).

  rf2-q9x6h — this used to read \"there are no armed timers
  (`:cancelled` rings are static)\", and `:cancelled` rings STOPPED
  being static when rf2-y8doi.23 gave them a retention window. They
  have a DEADLINE now, and a deadline needs a clock to reach it. Both
  gates read this predicate — `tick-loop!` to decide whether to
  re-schedule, `overlay-tree` to decide whether to kick — so in an
  otherwise idle LIVE chart, cancelling the LAST armed timer froze
  `:rings/now-ms` at that instant, `now-ms` never reached `:closed-at`
  + the window, [[prune-timers]] never evicted, and the crossed ring
  stayed on screen FOR EVER.

  IT STILL STOPS, which is the other half of the contract: the window
  is bounded, so once the last cancelled ring ages out this goes false
  and the loop stops re-scheduling. The clock outlives the last armed
  timer by at most [[cancelled-retention-ms]] — one per chart, never
  one per ring (Lock #8).

  Pure fn — keeps the rAF gate testable. The view passes the result
  to a side-effect that starts / stops the loop."
  [timers scrubber-position now-ms]
  (and (= :present scrubber-position)
       (boolean
         (some (fn [t]
                 (or (= :armed (:status t))
                     (cancelled-ring-live? t now-ms)))
               (or timers [])))))

;; ---- retro now-ms anchor (rf2-8i1tg3 · xray/003 §M.2) --------------------

(defn focused-cascade-time-ms
  "Pull the focused event-bundle's dispatched wall-clock time (ms) off
  the `:rf.xray/focused-event-bundle-detail` composite value — the SAME
  `[:dispatched :time]` slot the L2 event list's timestamp column reads
  (`shell/event-bundle-dispatched-time-ms`). Returns nil when there is
  no selected event-bundle, or its `:dispatched :time` is not a number
  (defence — a synthetic/legacy event-bundle that omits the field must
  not freeze the ring at a bogus anchor)."
  [focused-event-bundle-detail]
  (let [t (get-in focused-event-bundle-detail
                  [:selected-event-bundle :dispatched :time])]
    (when (number? t) t)))

(defn resolve-now-ms
  "Resolve the `now-ms` anchor `timers->ring-specs` should project
  against.

  - `:present` `scrubber-position` (LIVE mode): `live-now-ms` — the
    rAF-bumped wall clock, so rings sweep in real time.
  - Any other `scrubber-position` (RETRO mode): per xray/003 §M.2 'the
    ring is static at the elapsed-fraction the timer had reached at
    the focused-cascade's timestamp' — `focused-cascade-ms`. Falls
    back to `live-now-ms` when the focused cascade carries no
    timestamp (defence — a nil anchor would blank every fraction
    calc rather than freeze it).

  Before rf2-8i1tg3 the view fed `live-now-ms` into `timers->ring-
  specs` UNCONDITIONALLY — `scrubber-position` gated only whether the
  rAF loop kept ticking, so leaving LIVE mode simply stopped the clock
  wherever it last was instead of anchoring to the focused cascade the
  spec promises. Pure fn — the view supplies both candidate
  timestamps; kept testable without a DOM/subscribe harness."
  [scrubber-position live-now-ms focused-cascade-ms]
  (if (= :present scrubber-position)
    live-now-ms
    (or focused-cascade-ms live-now-ms)))
