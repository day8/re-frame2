(ns day8.re-frame2-xray.theme.perf-tier
  "Shared perf-tier classification — the canonical duration→tier→colour
  ladder per `tools/xray/spec/007-UX-IA.md` §Colour system §Perf scale.

  ## Why this lives in `theme/`

  Inspired by Vue DevTools 3's colour-coded lifecycle hook durations
  (green ≤10ms / yellow >10ms / red >30ms). Spec/007-UX-IA.md
  §'Colour system' defines four tiers — `:fast` / `:medium` / `:slow`
  / `:blocking` (<16 / 16-50 / 50-100 / >100ms, where 100ms is the
  INP-blocking threshold). Every node carrying a measurable duration
  surfaces the tier-coloured dot the same way: same hex, same
  glyph shape, same label string.

  The classification ladder is design-system grade — one source of
  truth, spec-anchored, JVM-portable — so it lives in `theme/` and is
  discoverable from anywhere in `panels/` without a cross-panel
  require chain. The Performance panel re-exports from here.

  ## Panel ledger — surfaces that DO / DO NOT yet show tier dots

    Panel                Surface that should carry the tier-dot   Status
    -----                --------------------------------------   ------
    performance          cascade-row, breakdown-bar, tier-chip    LIVE
    event_detail         handler-row :duration-ms slot            LIVE
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

  ## Pure data, JVM-portable

  Everything here is pure data → pure data. `.cljc` so the helpers
  work from both the Performance panel's projection (Clojure-side
  tests) and the view layer (ClojureScript runtime)."
  {:no-doc true}
  (:require [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- ladder --------------------------------------------------------------

(def tier-order
  "Render order for the perf tiers when a panel renders a histogram
  or chip row. Fastest first because the goal is 'mostly green'."
  [:fast :medium :slow :blocking])

(defn classify-tier
  "Map a `duration-ms` (number) to one of the four perf tiers per
  `tools/xray/spec/007-UX-IA.md` §Colour system:

      <16ms     → :fast       one-frame-at-60fps
      16-50ms   → :medium
      50-100ms  → :slow
      >=100ms   → :blocking   INP-blocking band

  Boundaries are right-open at the lower edge (the next tier owns the
  threshold value itself). Negative / nil / non-numeric durations
  classify as `:fast` — a robust default for pipeline runs whose `:time`
  deltas collapse to zero (single-event runs).

  Pure data → keyword; JVM-testable."
  [duration-ms]
  (cond
    (not (number? duration-ms)) :fast
    (< duration-ms 16)          :fast
    (< duration-ms 50)          :medium
    (< duration-ms 100)         :slow
    :else                       :blocking))

(def tier->token
  "Pure semantic map from perf-tier keyword to token keyword. Mirrors
  `spec/007-UX-IA.md` §Colour system §Perf scale. The hex resolution
  happens via `tier-colour` which looks up `theme/tokens`; this map
  is the JVM-portable pure-data layer that tests can drive without
  depending on a CLJS-only constant."
  {:fast     :green
   :medium   :yellow
   :slow     :orange
   :blocking :red})

(defn tier-colour
  "Hex swatch per perf tier. Resolves the semantic token keyword
  (`tier->token`) through the canonical `theme/tokens` map so the
  palette has exactly one source of truth. Falls back
  to `:text-tertiary` for unknown tiers."
  [tier]
  (get tokens/tokens
       (get tier->token tier :text-tertiary)))

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
