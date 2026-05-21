(ns day8.re-frame2-machines-viz.visual-constants
  "Chart visual constants — single source for the geometry + typography
  + motion durations that shape the MachineChart's character.

  rf2-g6cig — the chart was previously sprinkled with magic numbers
  (`corner-radius 8`, `stroke-width 1.5`, `pad-x 16`, font sizes 9 / 7,
  etc.). Lifting them into one map keeps the chart's visual character
  legible at a glance.

  rf2-32gw5 — the chart ships THREE density variants:
  `chart-compact`, `chart-regular`, `chart-cosy`. The previous
  docstring 'a future density toggle (compact / cosy / comfy)' was a
  forward-promise that has now landed. The naming settled on
  `compact / regular / cosy` (per bead title); `regular` is the
  load-bearing chart-floor floor (per rf2-gg7ws) and the default
  every consumer gets when `:density` is unspecified or `nil`.
  `compact` shrinks geometry + typography proportionally for grid
  layouts (Story's 50-chart panel grid); `cosy` widens both for the
  single-chart-display case (Causa's machines tab on a wide
  monitor). Hosts pick one via the chart's `:density` prop. The
  three maps share the SAME key set (asserted by
  `visual-constants-cljs-test`); a key in one is a key in all.

  rf2-cd053 / rf2-gg7ws — `chart-regular` state-label + edge-label
  font sizes walked up from the previous spec/007-UX-IA refused-floor
  (11 / 9) to a chart-appropriate 13 / 11 per the 2026-05-20 visual-
  quality lift. The refused-floor was set for dense data-grid
  surfaces; applying it to a chart that competes with xstate-stately's
  typography was a category error. `chart-compact` deliberately walks
  back to 11/9 since the compact density IS the dense-grid surface
  the original floor existed for; `chart-cosy` walks up to 15/13.

  rf2-g6cig — corner-radius locked at 6px across every density. The
  React Flow default (8) reads as 'product chrome'; brutalist (0)
  reads as 'wireframe'; 6 is the sweet spot — soft enough to feel
  finished, sharp enough to read as 'data, not product'. Locked
  2026-05-19. Density does not unlock this.

  rf2-2sez0 — heartbeat-pulse animation removed 2026-05-20. The
  active state's static affordance (cyan tint + emphasised stroke)
  carries the 'currently here' signal without a continuous loop;
  the transition glow on event-fire remains for moment-of-cause
  cueing. `:pulse-stroke-width-add` retired with the animation.

  rf2-k647w — this ns is now the SINGLE SOURCE of the chart's render
  geometry/typography. The xyflow `MachineChart` (`chart.nodes` /
  `chart.edges` / `chart.cljs`) used to HARDCODE the regular-density
  numbers and had DRIFTED from `chart-regular` (tag-pill height 16 vs.
  spec'd 12, pad-x 6 vs. 5, px 9 vs. 8, row-gap 3 vs. 4). The
  reconciliation moves `chart-regular` to the SHIPPED renderer numbers
  (so wiring `:density` introduces no visual regression at the default)
  and adds `:compound-radius` + `:tag-pill-radius` — two render
  constants the renderer hardcoded but `visual-constants` did not yet
  carry. The renderer now READS every one of these off the resolved
  density map (threaded through the projector's per-node/per-edge
  `:data`); switching `:density` changes the render for real and the
  chart root emits `data-density`."
  {:no-doc true})

(def chart-regular
  "Chart visual constants — REGULAR density (the default).

  Keys:

    :corner-radius            — node rounded-corner radius in px
                                (rf2-g6cig lock: 6 across every
                                density)
    :stroke-width             — default node + edge stroke width
    :stroke-width-emphasis    — emphasised node/edge stroke width
                                (active / focused-event lens)
    :compound-pad-x           — horizontal inset on compound
                                container around its children
    :compound-pad-y           — vertical inset (extra top padding
                                leaves room for the title strip)
    :compound-stroke-dash     — dashed pattern for compound borders
    :state-label-px           — state node label font-size
                                (rf2-gg7ws lift — regular: 13)
    :edge-label-px            — edge label font-size
                                (rf2-gg7ws lift — regular: 11)
    :edge-label-backplate-opacity
                              — opacity of the small white rect
                                painted behind each edge label so
                                overlapping labels remain legible
                                (rf2-gg7ws — collision-avoidance v1)
    :final-glyph-px           — final-state ✓ glyph font-size
    :compound-title-px        — compound container title font-size
    :caption-strip-px         — chart-level caption strip height
                                (rf2-3zdzw)
    :caption-text-px          — caption strip text font-size
    :compound-radius          — compound container corner radius
                                (rf2-k647w — distinct from the
                                state-node `:corner-radius` lock; the
                                compound chrome reads as a looser box)
    :tag-pill-height          — state-tag pill height (rf2-m1b88)
    :tag-pill-pad-x           — state-tag pill horizontal padding
    :tag-pill-px              — state-tag pill text font-size
    :tag-pill-radius          — state-tag pill corner radius
                                (rf2-k647w — the pill's own rounding;
                                tracks density, unlike the state-node
                                `:corner-radius` lock)
    :tag-pill-gap             — horizontal gap between adjacent
                                pills
    :tag-pill-row-gap         — vertical gap from pill row to
                                state label
    :dot-grid-spacing-px      — dot-grid background pattern spacing
                                (rf2-m4nj4 — 16px)
    :dot-grid-radius-px       — single dot radius
    :dot-grid-alpha           — dot opacity (0..1)"
  {;; ── geometry ─────────────────────────────────────────────────
   :corner-radius          6
   :stroke-width           1.5
   :stroke-width-emphasis  2.5
   :compound-pad-x         16
   :compound-pad-y         24
   :compound-stroke-dash   "4 3"
   :compound-radius        10

   ;; ── typography (rf2-gg7ws — chart-regular floor 13/11) ───────
   :state-label-px         13
   :edge-label-px          11
   :edge-label-backplate-opacity 0.85
   :final-glyph-px         13
   :compound-title-px      13
   :caption-strip-px       28
   :caption-text-px        11

   ;; ── state-tag pills (rf2-m1b88; rf2-k647w drift reconciled to
   ;;    the SHIPPED renderer numbers — height 16 / pad-x 6 / px 9 /
   ;;    radius 8 / row-gap 3 — so wiring :density introduces NO
   ;;    visual regression at the regular default) ─────────────────
   :tag-pill-height        16
   :tag-pill-pad-x         6
   :tag-pill-px            9
   :tag-pill-radius        8
   :tag-pill-gap           3
   :tag-pill-row-gap       3

   ;; ── dot-grid background (rf2-m4nj4) ──────────────────────────
   :dot-grid-spacing-px    16
   :dot-grid-radius-px     1.0
   :dot-grid-alpha         0.06})

(def chart-compact
  "Chart visual constants — COMPACT density.

  Smaller nodes, smaller type, tighter gaps. Targets the 50-chart
  Story grid where each chart is a thumbnail and the user's eye
  scans the grid for shape rather than reading individual labels.

  Walks the typography back to the spec/007-UX-IA refused-floor
  (11 / 9) — the refused-floor was set for dense data-grid surfaces,
  and the compact density IS the dense-grid surface it existed for.

  Geometry is pulled in by ~25% (paddings, pill height, dot-grid
  spacing); corner-radius is unchanged because the rf2-g6cig lock
  applies across every density (a wireframe-looking thumbnail is
  still wrong).

  Shares the SAME key set as `chart-regular` (asserted by
  visual-constants-cljs-test rf2-32gw5)."
  {;; ── geometry (~25% tighter) ──────────────────────────────────
   :corner-radius          6           ;; rf2-g6cig lock — same in every density
   :stroke-width           1.0
   :stroke-width-emphasis  2.0
   :compound-pad-x         10
   :compound-pad-y         18
   :compound-stroke-dash   "3 2"
   :compound-radius        8           ;; ~25% tighter than regular's 10

   ;; ── typography (rf2-gg7ws refused-floor revisited for thumbnails)
   :state-label-px         11
   :edge-label-px          9
   :edge-label-backplate-opacity 0.85
   :final-glyph-px         11
   :compound-title-px      11
   :caption-strip-px       22
   :caption-text-px        9

   ;; ── state-tag pills (tighter than the regular 16/6/9/8) ──────
   :tag-pill-height        13
   :tag-pill-pad-x         5
   :tag-pill-px            7
   :tag-pill-radius        6
   :tag-pill-gap           2
   :tag-pill-row-gap       2

   ;; ── dot-grid background ──────────────────────────────────────
   :dot-grid-spacing-px    12
   :dot-grid-radius-px     0.85
   :dot-grid-alpha         0.06})

(def chart-cosy
  "Chart visual constants — COSY density.

  Larger nodes, larger type, more breathing room. Targets the
  single-chart-display case — Causa's machines tab on a wide monitor
  or a presentation-mode standalone viewer. The user is reading the
  chart, not scanning a grid; labels are payload, not decoration.

  Walks the typography up to 15 / 13. Geometry widens by ~25%
  (paddings, pill height, dot-grid spacing). Corner-radius is
  unchanged per the rf2-g6cig lock — the chart's visual character
  must read consistently across densities; only quantity scales,
  not identity.

  Shares the SAME key set as `chart-regular` (asserted by
  visual-constants-cljs-test rf2-32gw5)."
  {;; ── geometry (~25% looser) ───────────────────────────────────
   :corner-radius          6           ;; rf2-g6cig lock — same in every density
   :stroke-width           1.75
   :stroke-width-emphasis  3.0
   :compound-pad-x         20
   :compound-pad-y         30
   :compound-stroke-dash   "5 4"
   :compound-radius        12          ;; ~25% looser than regular's 10

   ;; ── typography (walked up one notch from the regular floor) ──
   :state-label-px         15
   :edge-label-px          13
   :edge-label-backplate-opacity 0.85
   :final-glyph-px         15
   :compound-title-px      15
   :caption-strip-px       34
   :caption-text-px        13

   ;; ── state-tag pills (looser than the regular 16/6/9/8) ───────
   :tag-pill-height        19
   :tag-pill-pad-x         7
   :tag-pill-px            11
   :tag-pill-radius        10
   :tag-pill-gap           4
   :tag-pill-row-gap       4

   ;; ── dot-grid background ──────────────────────────────────────
   :dot-grid-spacing-px    20
   :dot-grid-radius-px     1.15
   :dot-grid-alpha         0.06})

(def chart
  "Default chart visual constants — alias for `chart-regular`.

  Direct consumers may continue to reference `vc/chart` for the
  regular-density map. The `MachineChart` component's `:density`
  prop (per `tools/machines-viz/spec/API.md` §Density) picks one of
  the three named maps at render time; this Var is the resolved
  value when `:density` is unspecified."
  chart-regular)

(def densities
  "The complete catalogue of density keywords accepted by
  `chart-for-density` (and, transitively, the `MachineChart`
  `:density` prop). Exposed as a Var so hosts that want to render a
  picker UI can enumerate the choices without hardcoding."
  [:compact :regular :cosy])

(def ^:private density->chart-map
  {:compact chart-compact
   :regular chart-regular
   :cosy    chart-cosy})

(defn chart-for-density
  "Resolve a density keyword to its chart visual-constants map.

  - `:compact`  → `chart-compact`  (thumbnail-grid density)
  - `:regular`  → `chart-regular`  (default — chart-appropriate floor)
  - `:cosy`     → `chart-cosy`     (presentation density)
  - `nil`       → `chart-regular`  (the implicit default)
  - any other → throws `ex-info` with the offending value; an
                unrecognised density is a programmer error, not a
                runtime fallback (per rf2-32gw5: hosts pick from the
                closed set or the chart rejects).

  Pure fn — JVM-runnable. The MachineChart component calls this once
  per render, then threads the resolved map through every helper."
  [density]
  (cond
    (nil? density) chart-regular
    (contains? density->chart-map density) (get density->chart-map density)
    :else
    (throw (ex-info ":rf.error/machines-viz-unknown-chart-density"
                    {:rf.error/id :rf.error/machines-viz-unknown-chart-density
                     :where     'machines-viz/resolve-chart-density
                     :recovery  :no-recovery
                     :reason    (str "unknown chart density: " (pr-str density)
                                     ". Expected one of " (pr-str densities) ".")
                     :density   density
                     :expected  densities}))))
