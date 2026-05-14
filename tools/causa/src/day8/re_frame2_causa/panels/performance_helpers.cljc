(ns day8.re-frame2-causa.panels.performance-helpers
  "Pure-data helpers for Causa's Performance panel (Phase 5, rf2-75121).

  ## Why a separate `.cljc` ns

  The panel view in `performance.cljs` paints per-cascade duration rows
  and dispatches into the Causa frame. The *logic* — folding the trace
  buffer into per-cascade duration records, classifying each cascade
  against the perf-tier scale (fast / medium / slow / blocking), and
  marking over-budget rows — is pure data → data. Splitting the algebra
  into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Spec substrate

  Two surfaces feed this panel:

    - **Trace stream** (Spec 009 §Subscription / consumption) — every
      emit carries `:id`, `:time` (host-clock ms), `:op-type`,
      `:operation`, `:tags`. The stream is event-at-a-time; there is
      no first-class `:duration` field on the trace event itself (per
      Spec 009 L38: 'no separate start/end pair, no :duration, no
      :child-of'). The Performance panel derives cascade-level
      durations from the `:time` deltas across a cascade's events.

    - **User Timing surface** (Spec 009 §Performance instrumentation,
      `re-frame.performance/enabled?` flag) — `rf:event:*`, `rf:sub:*`,
      `rf:fx:*`, `rf:render:*` measure entries with `name`, `startTime`,
      `duration`. This is the prod-friendly default-off channel; v1
      Performance panel reads the trace stream instead since:

        1. Dev builds carry the trace stream by default
           (`goog.DEBUG=true`); flipping a *second* compile-time
           constant just to use the panel is friction.
        2. The User Timing entries are per-mark, not per-cascade —
           reassembling them into a six-domino cascade requires the
           same `:dispatch-id` correlation the trace stream already
           ships, so reading the trace stream is the shorter path.

      The User Timing surface remains the right consumer for an APM
      pipeline (Chrome DevTools' Performance panel, `PerformanceObserver`
      sinks); the Causa Performance panel and the User Timing channel
      coexist as distinct consumers of distinct surfaces.

  ## Perf tier scale (per `tools/causa/spec/007-UX-IA.md` §Colour system)

  The colour-only signal is paired with a glyph per spec §Colour is
  never alone:

      :fast      <16ms       green   #4ADE80   ●
      :medium    16-50ms     yellow  #FBBF24   ●
      :slow      50-100ms    orange  #FB923C   ▲
      :blocking  >100ms      red     #F87171   ▲    INP threshold

  Thresholds are right-open (`<16` ∧ `≥16` boundary). `:fast` is the
  one-frame-at-60fps target; `:blocking` is the INP-blocking band per
  the Web Vitals `INP` definition (>200ms is poor; >100ms is needs-
  improvement — the spec collapses both into the blocking band so the
  programmer's eye is drawn to *anything* the user perceives as a hang).

  ## Budget warnings

  The 'budget' is the configurable threshold above which a cascade
  carries a warning marker. v1 default = 16ms (one frame at 60fps),
  which makes `:medium` / `:slow` / `:blocking` all over-budget. The
  threshold is a sub-readable slot on Causa's app-db
  (`:performance-budget-ms`); a follow-on bead may add a slider in the
  panel header (currently set programmatically via
  `:rf.causa/set-performance-budget-ms`).

  ## Cascade duration model

  The trace events for one cascade share a `:dispatch-id`. The
  cascade's total duration is `(- (max :time) (min :time))` across
  every event carrying that `:dispatch-id`. The per-step breakdown is
  computed from the same time deltas:

      handler-ms  — first :handler trace's :time → last :handler's :time
      fx-ms       — :event/do-fx :time → last :effect's :time
      sub-ms      — first :sub run/create :time → last :sub :time
      render-ms   — first :render :time → last :render :time

  Each slice is non-overlapping by construction (the six-domino order
  is event → handler → fx → effects → subs → renders) — but **the
  slices are derived, not authoritative**; the trace stream is event-
  at-a-time and the actual call graph can interleave (e.g. a sub recompute
  may fire mid-render). The slice is the 'where in the cascade did time
  go' approximation, not a profiler-grade attribution.

  ## What's NOT in v1

  Per the bead's minimum-viable contract:

    - No `PerformanceObserver` integration (INP / long-task / layout-
      shift overlays). The bead description sketches these but they
      ride the `rf.causa.fx/install-performance-observer!` effect
      which has not landed.
    - No drag-zoom on a horizontal ribbon. v1 ships a tabular row
      view; the ribbon canvas is follow-on work.
    - No re-render-counts-per-epoch projection; the row's render-count
      is the count of `:view/render` traces in the cascade, which is a
      cheap proxy until the epoch-record's `:renders` projection
      surfaces.

  ## Tier ladder lives in `theme/perf_tier.cljc` (rf2-6ja23)

  `classify-tier` / `tier-colour` / `tier-glyph` / `tier-label` /
  `over-budget?` / `default-budget-ms` / `tier-order` are re-exported
  here so the Performance panel's existing call-sites keep resolving
  through `h/classify-tier` etc. The canonical definitions live in
  `day8.re-frame2-causa.theme.perf-tier` so every other panel surface
  that needs to colour a duration reads the same ladder. See that
  ns's docstring for the audit ledger of who's wired and who's
  blocked on the trace stream growing per-event `:duration-ms`."
  (:require [day8.re-frame2-causa.theme.perf-tier :as perf-tier]))

;; ---- defaults ------------------------------------------------------------

(def default-budget-ms
  "Re-export of `theme.perf-tier/default-budget-ms` (rf2-6ja23). The
  canonical ladder lives in `theme/perf_tier.cljc`; this var is held
  here so existing `performance_helpers/default-budget-ms` references
  keep resolving."
  perf-tier/default-budget-ms)

(def tier-order
  "Re-export of `theme.perf-tier/tier-order`."
  perf-tier/tier-order)

;; ---- tier classification -------------------------------------------------

(defn classify-tier
  "Re-export of `theme.perf-tier/classify-tier`. See that ns for the
  ladder definition + boundary semantics."
  [duration-ms]
  (perf-tier/classify-tier duration-ms))

(defn tier-colour
  "Re-export of `theme.perf-tier/tier-colour`."
  [tier]
  (perf-tier/tier-colour tier))

(defn tier-glyph
  "Re-export of `theme.perf-tier/tier-glyph`."
  [tier]
  (perf-tier/tier-glyph tier))

(defn tier-label
  "Re-export of `theme.perf-tier/tier-label`."
  [tier]
  (perf-tier/tier-label tier))

(defn over-budget?
  "Re-export of `theme.perf-tier/over-budget?`."
  [budget-ms duration-ms]
  (perf-tier/over-budget? budget-ms duration-ms))

;; ---- duration projection -------------------------------------------------

(defn- event-time
  "Lift `:time` off a trace event. nil-safe — returns nil when the
  event is nil or carries no :time."
  [ev]
  (when (map? ev)
    (let [t (:time ev)]
      (when (number? t) t))))

(defn- bookend
  "Min / max of `:time` across a non-empty sequence of trace events.
  Returns `nil` when `evs` is empty or every event lacks `:time`."
  [evs]
  (let [times (keep event-time evs)]
    (when (seq times)
      [(apply min times) (apply max times)])))

(defn slice-duration
  "Compute the duration of one cascade slice — `(max :time) - (min
  :time)` across `evs`. Pure data → number-or-nil; JVM-testable.

  Returns `nil` when `evs` is empty / every event lacks :time. Returns
  `0` when only one event has a :time (the bookends collapse)."
  [evs]
  (when-let [[t-min t-max] (bookend evs)]
    (- t-max t-min)))

(defn cascade-events
  "Collect every trace event in `cascade` into one sequence — the six
  domino slots plus the `:other` bucket. Used to compute the cascade's
  total wall-clock span. Pure data → seq; JVM-testable."
  [{:keys [handler fx effects subs renders other] :as _cascade}]
  (concat (when handler [handler])
          (when fx [fx])
          effects
          subs
          renders
          other))

(defn cascade-duration
  "Total wall-clock duration of `cascade` in ms — `(max :time) - (min
  :time)` across every event in the cascade. nil when no event carries
  a :time. Pure data → number-or-nil; JVM-testable."
  [cascade]
  (slice-duration (cascade-events cascade)))

(defn step-breakdown
  "Per-step duration breakdown for one cascade. Returns a map keyed by
  the six-domino bucket; each value is ms-or-nil.

  `:handler` is a single event (the `:run-end` emit), so its
  contribution is collapsed under the handler-and-fx span:

      :handler  — wall-clock from :handler.time → :fx.time
      :fx       — :fx.time → max of :effects :time
      :effects  — min :effects :time → max :effects :time (or nil)
      :subs     — slice-duration over :subs
      :renders  — slice-duration over :renders

  Each slice is independent — they may overlap with each other in
  wall-clock time when the trace stream interleaves (e.g. a sub
  recompute fires during render). v1 ships the simple model; a
  follow-on bead can lift this into a profiler-grade attribution
  once the trace stream carries per-event `:duration-ms`.

  Pure data → map; JVM-testable."
  [{:keys [handler fx effects subs renders] :as _cascade}]
  (let [t-handler (event-time handler)
        t-fx      (event-time fx)
        eff-min   (some-> (bookend effects) first)
        eff-max   (some-> (bookend effects) second)]
    {:handler  (when (and t-handler t-fx)
                 (max 0 (- t-fx t-handler)))
     :fx       (cond
                 (and t-fx eff-max) (max 0 (- eff-max t-fx))
                 t-fx               0
                 :else              nil)
     :effects  (when (and eff-min eff-max)
                 (- eff-max eff-min))
     :subs     (slice-duration subs)
     :renders  (slice-duration renders)}))

;; ---- per-cascade projection ---------------------------------------------

(defn- safe-event-vec
  "Pluck the event vector from a cascade record. Falls back to
  `[:ungrouped]` for free-floating events / cascades whose dispatched
  trace isn't in the buffer. Pure data → vector."
  [{:keys [event] :as _cascade}]
  (or event [:ungrouped]))

(defn project-cascade
  "Project one cascade record into a Performance panel row:

      {:dispatch-id  <id-or-:ungrouped>
       :event        <event-vec>
       :duration-ms  <number-or-nil>
       :tier         <:fast :medium :slow :blocking>
       :over-budget? <bool>
       :breakdown    {handler fx effects subs renders}
       :render-count <int>
       :effect-count <int>
       :sub-count    <int>}

  Pure data → map; JVM-testable. `budget-ms` is the over-budget
  threshold; nil disables the over-budget axis (every row classifies
  as within-budget)."
  [budget-ms {:keys [dispatch-id effects subs renders] :as cascade}]
  (let [duration  (cascade-duration cascade)
        tier      (classify-tier duration)
        breakdown (step-breakdown cascade)]
    {:dispatch-id  dispatch-id
     :event        (safe-event-vec cascade)
     :duration-ms  duration
     :tier         tier
     :over-budget? (over-budget? budget-ms duration)
     :breakdown    breakdown
     :render-count (count renders)
     :effect-count (count effects)
     :sub-count    (count subs)}))

(defn project-cascades
  "Project every cascade record in `cascades` into a vector of
  Performance rows. Cascades whose `:dispatch-id` is `:ungrouped`
  are dropped — they represent free-floating events (registry-time
  emits, frame lifecycle outside a drain) that don't carry a
  meaningful cascade duration. Pure data → vector; JVM-testable."
  [budget-ms cascades]
  (into []
        (comp (remove #(= :ungrouped (:dispatch-id %)))
              (map #(project-cascade budget-ms %)))
        cascades))

;; ---- aggregates ----------------------------------------------------------

(defn tier-counts
  "Histogram of rows by perf tier — used for the panel header summary.
  Returns a map keyed by every tier in `tier-order` so the chip-row
  renders with zero counts for missing tiers (no flickering chip set).
  Pure data → map; JVM-testable."
  [rows]
  (let [base (zipmap tier-order (repeat 0))]
    (reduce
      (fn [acc {:keys [tier]}]
        (update acc tier (fnil inc 0)))
      base
      rows)))

(defn over-budget-count
  "Number of rows marked `:over-budget?`. Pure data → int; JVM-
  testable."
  [rows]
  (count (filter :over-budget? rows)))

;; ---- formatting ----------------------------------------------------------

(defn format-duration
  "Render `duration-ms` as a human-readable string. nil → `\"—\"`.
  Decimals collapsed at the ms level (sub-millisecond resolution
  isn't useful for the cascade view). Pure data → string; JVM-
  testable."
  [duration-ms]
  (cond
    (not (number? duration-ms)) "—"
    (< duration-ms 1)           "<1ms"
    :else                       (str (long duration-ms) "ms")))

(defn format-event
  "Render the event vector as a compact one-line string for the row
  label. Falls back to `(str event)` when `pr-str` throws. Pure data
  → string; JVM-testable."
  [event-vec]
  (try
    (pr-str event-vec)
    (catch #?(:clj Throwable :cljs :default) _
      (str event-vec))))

(defn truncate
  "Truncate `s` to `n` chars (with an ellipsis when truncated). The
  row label uses this to fit inside the panel's fixed grid column.
  Pure data → string; JVM-testable."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (dec n))) "…"))))

;; ---- composite projection (the panel reads this) -----------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs in one
  pass. Pure data → data; JVM-testable.

  Returns:

      {:rows               [<row> ...]      ;; newest first
       :total              <int>            ;; pre-filter count
       :tier-counts        {tier count}     ;; histogram across rows
       :over-budget-count  <int>
       :budget-ms          <number>
       :empty?             <bool>}

  `cascades` is the six-domino projection over the raw trace buffer
  (use `re-frame.trace.projection/group-cascades`). `budget-ms` is the
  over-budget threshold; nil falls back to `default-budget-ms`.

  `:empty?` is true when no cascades carry a dispatch-id (e.g. only
  free-floating registry traces have landed). The view paints the
  empty-state in that case."
  [cascades budget-ms]
  (let [budget         (or budget-ms default-budget-ms)
        rows           (project-cascades budget cascades)
        ;; Newest first — the panel header summarises the latest
        ;; activity at-a-glance. Per cascade-ordering convention in
        ;; group-cascades the input is oldest-first.
        sorted-display (vec (reverse rows))]
    {:rows              sorted-display
     :total             (count rows)
     :tier-counts       (tier-counts rows)
     :over-budget-count (over-budget-count rows)
     :budget-ms         budget
     :empty?            (empty? rows)}))

;; ---- lookup ---------------------------------------------------------------

(defn find-row
  "Look up a projected row by `:dispatch-id`. Returns nil when not
  found. Pure data → row-or-nil; JVM-testable."
  [rows dispatch-id]
  (some (fn [r] (when (= dispatch-id (:dispatch-id r)) r)) rows))

;; ---- breakdown helpers (view-side) --------------------------------------

(defn breakdown-segments
  "Lay out the per-step breakdown as a vector of `{:key :ms :width-pct}`
  segments. `total-ms` is the cascade's total duration (the bar's full
  width); each segment's `:width-pct` is the fraction of that total it
  occupies, clamped to `[0, 100]`.

  Slices whose ms is nil / zero are dropped. Pure data → vector;
  JVM-testable."
  [breakdown total-ms]
  (if (or (not (number? total-ms)) (<= total-ms 0))
    []
    (let [step-order [:handler :fx :effects :subs :renders]]
      (into []
            (keep (fn [k]
                    (when-let [ms (get breakdown k)]
                      (when (and (number? ms) (pos? ms))
                        {:key       k
                         :ms        ms
                         :width-pct (min 100.0
                                         (max 0.0
                                              (* 100.0 (/ ms total-ms))))})))
                  step-order)))))

(defn breakdown-colour
  "Colour swatch for a breakdown slice. Mirrors the per-domino tones
  used by `event_detail.cljs` so the cross-panel visual is consistent:

      :handler  cyan
      :fx       cyan
      :effects  green
      :subs     cyan
      :renders  magenta

  Pure data → hex string."
  [step]
  (case step
    :handler  "#43C3D0"  ; cyan
    :fx       "#43C3D0"  ; cyan
    :effects  "#4ADE80"  ; green
    :subs     "#43C3D0"  ; cyan
    :renders  "#E879F9"  ; magenta
    "#6B7080"))

(defn breakdown-label
  "Human-readable label for a breakdown slice."
  [step]
  (case step
    :handler  "handler"
    :fx       "fx"
    :effects  "effects"
    :subs     "subs"
    :renders  "renders"
    (str step)))
