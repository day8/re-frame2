(ns day8.re-frame2-causa.theme.perf-tier
  "Shared perf-tier classification — the canonical duration→tier→colour
  ladder per `tools/causa/spec/007-UX-IA.md` §Colour system §Perf scale.

  ## Why this lives in `theme/` (rf2-6ja23)

  Inspired by Vue DevTools 3's colour-coded lifecycle hook durations
  (green ≤10ms / yellow >10ms / red >30ms). Spec/007-UX-IA.md
  §'Colour system' defines four tiers — `:fast` / `:medium` / `:slow`
  / `:blocking` (<16 / 16-50 / 50-100 / >100ms, where 100ms is the
  INP-blocking threshold). Every node carrying a measurable duration
  should surface the tier-coloured dot the same way: same hex, same
  glyph shape, same label string.

  Originally the tier helpers were buried in
  `panels/performance_helpers.cljc` and used only by the Performance
  panel. The audit bead rf2-6ja23 found that:

    - `event_detail` already surfaces `:duration-ms` on the handler row
      (as raw EDN text — no tier dot).
    - Every other panel that will eventually show per-node durations
      (`subscriptions` sub-run timings, `effects` fx-handler timings,
      `machine_inspector` guard-eval timings, `time_travel` cascade
      span) is blocked on the trace stream carrying per-event
      `:duration-ms`. Spec 009 L38: 'no separate start/end pair,
      no :duration, no duration-ms tag'.

  Hoisting the helpers to `theme/perf_tier.cljc` makes them
  discoverable from anywhere in `panels/` without a cross-panel
  require chain. The Performance panel re-exports from here (no
  behaviour change) so existing call-sites keep working.

  ## Audit ledger — panels that DO / DO NOT yet show tier dots

  Last reviewed 2026-05-14 against the Phase 5 panel set.

    Panel                Surface that should carry the tier-dot   Status
    -----                --------------------------------------   ------
    performance          cascade-row, breakdown-bar, tier-chip    LIVE
    event_detail         handler-row :duration-ms slot            LIVE (rf2-6ja23)
    subscriptions        per-sub :duration-ms (run timings)       blocked on trace-stream
    effects              per-fx :duration-ms (handler timings)    blocked on trace-stream
    machine_inspector    guard-eval :duration-ms                  blocked on trace-stream
    trace                per-event :duration-ms                   blocked on trace-stream
    time_travel          cascade total wall-clock                 follow-on bead

  'blocked on trace-stream' means the surface needs Spec 009 to grow
  a per-event `:duration-ms` tag (or equivalent) before the tier-dot
  can be wired in non-speculatively. The performance panel sidesteps
  this by computing `(max :time) - (min :time)` across cascade
  slices, which is a cheap proxy at the cascade level but doesn't
  attribute per-handler / per-sub.

  Hoisting to `theme/` is the audit's structural finding: the
  classification ladder is design-system grade (one source of truth,
  spec-anchored, JVM-portable), not panel-local.

  ## Pure data, JVM-portable

  Everything here is pure data → pure data. `.cljc` so the helpers
  work from both the Performance panel's projection (Clojure-side
  tests) and the view layer (ClojureScript runtime)."
  {:no-doc true})

;; ---- ladder --------------------------------------------------------------

(def tier-order
  "Render order for the perf tiers when a panel renders a histogram
  or chip row. Fastest first because the goal is 'mostly green'."
  [:fast :medium :slow :blocking])

(defn classify-tier
  "Map a `duration-ms` (number) to one of the four perf tiers per
  `tools/causa/spec/007-UX-IA.md` §Colour system:

      <16ms     → :fast       one-frame-at-60fps
      16-50ms   → :medium
      50-100ms  → :slow
      >=100ms   → :blocking   INP-blocking band

  Boundaries are right-open at the lower edge (the next tier owns the
  threshold value itself). Negative / nil / non-numeric durations
  classify as `:fast` — a robust default for cascades whose `:time`
  deltas collapse to zero (single-event cascades).

  Pure data → keyword; JVM-testable."
  [duration-ms]
  (cond
    (not (number? duration-ms)) :fast
    (< duration-ms 16)          :fast
    (< duration-ms 50)          :medium
    (< duration-ms 100)         :slow
    :else                       :blocking))

(defn tier-colour
  "Hex swatch per perf tier. Mirrors `spec/007-UX-IA.md` §Colour
  system §Perf scale. Returns hex strings so the helper stays pure
  (no token-map dependency)."
  [tier]
  (case tier
    :fast     "#4ADE80"  ; green
    :medium   "#FBBF24"  ; yellow
    :slow     "#FB923C"  ; orange
    :blocking "#F87171"  ; red
    "#6B7080"))           ; text-tertiary fallback

(defn tier-glyph
  "Single-character glyph paired with the tier colour per `spec/007-
  UX-IA.md` §'Colour is never alone'. `●` for fast/medium (within-
  budget, acceptable); `▲` for slow/blocking (over-budget, attention-
  needed). Pure data → string; JVM-testable."
  [tier]
  (case tier
    :fast     "●"
    :medium   "●"
    :slow     "▲"
    :blocking "▲"
    "○"))

(defn tier-label
  "Human-readable label for a tier — used in over-budget captions
  and chip-row tooltips."
  [tier]
  (case tier
    :fast     "fast"
    :medium   "medium"
    :slow     "slow"
    :blocking "blocking"
    (str tier)))

;; ---- budget axis ---------------------------------------------------------

(def default-budget-ms
  "Default budget threshold above which a row carries the over-budget
  warning marker. 16ms = one frame at 60fps — the v1 ergonomic
  default. Sub-readable so a follow-on bead can surface a slider in
  panel headers."
  16)

(defn over-budget?
  "True iff `duration-ms` is at or above `budget-ms`. nil /
  non-number inputs are treated as within-budget so a malformed
  cascade record doesn't false-flag. Pure data → bool; JVM-testable."
  [budget-ms duration-ms]
  (boolean
    (and (number? budget-ms)
         (number? duration-ms)
         (>= duration-ms budget-ms))))
