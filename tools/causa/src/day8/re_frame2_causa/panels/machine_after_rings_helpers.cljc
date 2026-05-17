(ns day8.re-frame2-causa.panels.machine-after-rings-helpers
  "Pure-data helpers for the Machine Inspector `:after` timer countdown
  rings (rf2-7hwwe).

  ## What this owns

  Translates Causa's trace ring buffer into a vector of *armed* `:after`
  timer records the chart can overlay as countdown rings around state
  nodes — the wall-clock-time visualisation arc per
  `spec/019-Cross-Cutting-Insight.md`.

  Each timer record:

      {:machine-id  <kw>      ;; the machine the timer belongs to
       :state       <kw|vec>  ;; the bearing state-node
       :armed-at    <int>     ;; trace event time (ms epoch)
       :fires-at    <int>     ;; armed-at + duration-ms
       :duration-ms <int>     ;; resolved delay
       :epoch       <int>     ;; the machine's :after-epoch when scheduled
       :status      <kw>      ;; :armed | :fired | :stale | :cancelled | :skipped
       :delay-key   <any>     ;; the original :after map key (literal/sub-vec/fn)
       :sub-id      <kw|nil>} ;; subscription id when :delay-source = :sub

  ## Source events (per Spec 005 §Delayed :after transitions)

  The runtime emits these on the trace bus:

    - `:rf.machine.timer/scheduled`             — arms a timer
    - `:rf.machine.timer/fired` (`:fired? true`)— normal expiry
    - `:rf.machine.timer/fired` (`:fired? false`)— guard-suppressed
    - `:rf.machine.timer/stale-after`           — epoch mismatch
    - `:rf.machine.timer/cancelled-on-resolution`— sub-vec re-resolution
    - `:rf.machine.timer/skipped-on-server`     — SSR no-op

  ## Folding algorithm

  Walk the trace buffer oldest-first. Each `scheduled` event opens a
  timer keyed by `(machine-id, state, epoch)`; a later `fired` /
  `stale-after` / `cancelled-on-resolution` event for the same key
  closes it. The latest event wins so re-schedules at the same key
  replace the prior record (matches the runtime's idempotent
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
    :rf.machine.timer/cancelled-on-resolution
    :rf.machine.timer/skipped-on-server})

(defn timer-event?
  "True iff `ev` carries one of the recognised timer operations."
  [ev]
  (and (map? ev)
       (contains? timer-operations (:operation ev))))

(defn- machine-id-of
  "Per Spec 009 the timer events stamp `:machine-id` under `:tags`. The
  `:scheduled` event has it at the top of the `:tags` map; the `:fired`
  / `:stale-after` events emit only the state (per
  `emit-pick-traces!` — the parent machine-id is implicit in the
  cascade context). The fallback walks `:tags :handler-id` (which the
  pure-transition emit DOES carry for fired events when present) and
  finally drops down to whatever the trace projection layer left in
  the top-level `:frame` slot.

  Returns nil when no machine-id can be resolved — the caller
  filters those out before folding."
  [ev]
  (or (get-in ev [:tags :machine-id])
      (get-in ev [:tags :handler-id])))

;; ---- timer key resolution -----------------------------------------------

(defn- timer-key
  "Composite key for the timer-table fold. `(machine-id, state, epoch)`
  is the runtime's identity tuple — re-scheduling at the same state
  bumps the epoch (per `re-frame.machines.transition/pick-after-
  transition`), so a fresh epoch ALWAYS starts a new record. Within an
  epoch the latest event wins (the runtime's invariant: at most one
  in-flight timer per (state, epoch)).

  When the closing trace event lacks an `:epoch` (the `:cancelled-on-
  resolution` shape historically didn't stamp epoch — it sent the
  cancellation reason instead), we fall back to `(machine-id, state,
  :*)` so closures match the most recent open record."
  [machine-id state epoch]
  {:machine-id machine-id
   :state      state
   :epoch      (or epoch :*)})

;; ---- public: project active timers --------------------------------------

(defn- update-or-cancel
  "Match a closing event against the open timer-table. When `epoch` is
  provided, exact match; when nil, match the most recent open record
  for `(machine-id, state)`. Returns `[k record-or-nil]` — `nil`
  record means no open entry to update.

  Pure fn — JVM-runnable."
  [open machine-id state epoch]
  (let [exact-k (timer-key machine-id state epoch)
        exact   (get open exact-k)]
    (if exact
      [exact-k exact]
      ;; Fall back to the latest open record for the (machine-id, state)
      ;; pair — the closing event didn't stamp epoch so we trust the
      ;; ordering of the buffer.
      (let [matches (filter (fn [[k _v]]
                              (and (= machine-id (:machine-id k))
                                   (= state      (:state k))))
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
    - A `:cancelled-on-resolution` flips it to `:cancelled` and
      retains the `:armed-at` / `:fires-at` so the view can render
      the ring at its last position with the crossed-out overlay.
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
            sub-id     (:sub-id tags)]
        (cond
          (nil? machine-id) open
          (nil? state)      open

          (= :rf.machine.timer/scheduled op)
          (assoc open (timer-key machine-id state epoch)
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
          (let [[k v] (update-or-cancel open machine-id state epoch)]
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
                [k v] (update-or-cancel open machine-id state stale-epoch)]
            (if v
              (assoc open k (assoc v :status :stale :closed-at t))
              open))

          (= :rf.machine.timer/cancelled-on-resolution op)
          (let [[k v] (update-or-cancel open machine-id state epoch)]
            (if v
              (assoc open k (assoc v :status :cancelled :closed-at t))
              open))

          (= :rf.machine.timer/skipped-on-server op)
          (assoc open (timer-key machine-id state epoch)
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
  "Project Causa's trace buffer into a flat vector of timer records
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

;; ---- geometry for the chart overlay -------------------------------------

(defn state-node-center
  "Resolve the `[cx cy r]` triple for a chart-node corresponding to
  `state`. `id-fn` is `chart.layout/highlight-id` (passed in to avoid
  a compile-time dep from this ns into the chart layout); `node-index`
  is the `{node-id node}` map produced by `arc-h/nodes->index`.

  The radius is half the longer node dimension + a small breathing
  gap so the ring sits OUTSIDE the node rectangle. Returns nil when
  the state has no corresponding node in the positioned graph."
  [state node-index id-fn]
  (when (and state id-fn node-index)
    (let [nid (id-fn state)
          n   (get node-index nid)]
      (when n
        (let [cx (+ (:x n) (quot (:width n) 2))
              cy (+ (:y n) (quot (:height n) 2))
              ;; Radius = half-diagonal + a 4px breathing gap so the
              ;; ring sits clearly outside the node's stroke.
              w  (:width n)
              h  (:height n)
              r  (+ 4 (quot (long (Math/ceil (Math/sqrt
                                               (+ (* w w) (* h h))))) 2))]
          [cx cy r])))))

(defn timers->ring-positions
  "For each armed/cancelled `timer`, resolve its centre + radius via
  `state-node-center`. Returns a vec of `{:timer <r> :cx :cy :r}` —
  the view maps over this to render each ring. Pure fn.

  Drops timers whose state has no corresponding chart node (e.g. a
  compound parent without leaves in the projected graph)."
  [timers node-index id-fn]
  (vec
    (keep
      (fn [t]
        (when-let [[cx cy r] (state-node-center (:state t) node-index id-fn)]
          {:timer t :cx cx :cy cy :r r}))
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
