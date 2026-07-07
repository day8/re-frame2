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
  (:require [clojure.string :as str]))

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

  Returns `[]` when `machine-id` is nil. Pure fn — JVM-runnable."
  [trace-buffer machine-id]
  (if (nil? machine-id)
    []
    (let [events (->> (or trace-buffer [])
                      (filter timer-event?)
                      (filter (fn [ev] (= machine-id (machine-id-of ev))))
                      ;; Oldest first — the fold relies on chronological
                      ;; order so a later cancellation overrides an earlier
                      ;; arming.
                      (sort-by (fn [ev] (or (:id ev) (:time ev) 0))))
          table  (fold-timer-events events)]
      (vec
        (sort-by (fn [r] (or (:armed-at r) 0))
                 (vals table))))))

(defn active-timers-for-machine
  "Return the timer records the chart should render rings for. Filters
  the projection to:

    - `:armed`        — countdown is in progress.
    - `:cancelled`    — show fade + diagonal cross until evicted (the
                        view decides retention; the helper returns the
                        record so the renderer has the data).

  Closed-state timers (`:fired`, `:stale`, `:guard-suppressed`,
  `:skipped`) drop out — the ring's purpose is to show wall-clock-
  pressure on the chart; a fired ring is just chart noise.

  `now-ms` is optional; when supplied, `:armed` records whose
  `:fires-at` is more than 5s in the past are dropped — protects
  against zombie projections when a `:fired` trace event was elided
  from the buffer (e.g. ring-buffer eviction)."
  ([trace-buffer machine-id]
   (active-timers-for-machine trace-buffer machine-id nil))
  ([trace-buffer machine-id now-ms]
   (let [zombie-threshold-ms 5000
         records (project-timers trace-buffer machine-id)]
     (vec
       (keep
         (fn [r]
           (case (:status r)
             :armed
             (cond
               (and now-ms (:fires-at r)
                    (> (- now-ms (:fires-at r)) zombie-threshold-ms))
               nil
               :else r)

             :cancelled r
             nil))
         records)))))

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

    - There are no armed timers (`:cancelled` rings are static).
    - The scrubber is NOT at `:present` (retrospective mode freezes
      every ring at the scrubber's anchor time).

  Pure fn — keeps the rAF gate testable. The view passes the result
  to a side-effect that starts / stops the loop."
  [timers scrubber-position]
  (and (= :present scrubber-position)
       (some (fn [t] (= :armed (:status t))) (or timers []))))

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
