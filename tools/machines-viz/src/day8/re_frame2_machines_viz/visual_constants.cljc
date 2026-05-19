(ns day8.re-frame2-machines-viz.visual-constants
  "Chart visual constants — single source for the geometry + typography
  + motion durations that shape the MachineChart's character.

  rf2-g6cig — the chart was previously sprinkled with magic numbers
  (`corner-radius 8`, `stroke-width 1.5`, `pad-x 16`, font sizes 9 / 7,
  etc.). Lifting them into one map keeps the chart's visual character
  legible at a glance and makes a future density toggle (compact /
  cosy / comfy) a one-knob change.

  rf2-cd053 — state-label + edge-label font sizes restored to the
  spec/007-UX-IA refused-floor: state labels at 11, edge labels at 9.
  The previous 9/7 walked under the floor in service of a layout-fit
  win; typography is load-bearing here, layout adapts via wider nodes
  (`:node-width-px` here, kept in lockstep with `chart.layout`).

  rf2-g6cig — corner-radius locked at 6px. The React Flow default
  (8) reads as 'product chrome'; brutalist (0) reads as 'wireframe';
  6 is the sweet spot — soft enough to feel finished, sharp enough
  to read as 'data, not product'. Locked 2026-05-19.

  rf2-xfx6l — pulse + glow durations live in `:motion` here (the
  durations); the CSS seam (`--rf-causa-motion-scale`) lives in
  `theme/tokens/motion` (the surface). Two halves of the same
  contract: this ns owns the numbers, that ns owns the seam name +
  the reduced-motion plumbing."
  {:no-doc true})

(def chart
  "Chart visual constants. Keys:

    :corner-radius            — node rounded-corner radius in px
                                (rf2-g6cig lock: 6)
    :stroke-width             — default node + edge stroke width
    :stroke-width-emphasis    — emphasised node/edge stroke width
                                (active / focused-event lens)
    :compound-pad-x           — horizontal inset on compound
                                container around its children
    :compound-pad-y           — vertical inset (extra top padding
                                leaves room for the title strip)
    :compound-stroke-dash     — dashed pattern for compound borders
    :state-label-px           — state node label font-size
                                (rf2-cd053 — refused-floor 11)
    :edge-label-px            — edge label font-size
                                (rf2-cd053 — refused-floor 9)
    :final-glyph-px           — final-state ✓ glyph font-size
    :compound-title-px        — compound container title font-size
    :caption-strip-px         — chart-level caption strip height
                                (rf2-3zdzw)
    :caption-text-px          — caption strip text font-size
    :tag-pill-height          — state-tag pill height (rf2-m1b88)
    :tag-pill-pad-x           — state-tag pill horizontal padding
    :tag-pill-px              — state-tag pill text font-size
    :tag-pill-gap             — horizontal gap between adjacent
                                pills
    :tag-pill-row-gap         — vertical gap from pill row to
                                state label
    :dot-grid-spacing-px      — dot-grid background pattern spacing
                                (rf2-m4nj4 — 16px)
    :dot-grid-radius-px       — single dot radius
    :dot-grid-alpha           — dot opacity (0..1)
    :pulse-stroke-width-add   — extra stroke width on the pulse-
                                target node so the eye reads the
                                animation as a 'breath' (rf2-xfx6l)"
  {;; ── geometry ─────────────────────────────────────────────────
   :corner-radius          6
   :stroke-width           1.5
   :stroke-width-emphasis  2.5
   :compound-pad-x         16
   :compound-pad-y         24
   :compound-stroke-dash   "4 3"

   ;; ── typography ───────────────────────────────────────────────
   :state-label-px         11
   :edge-label-px          9
   :final-glyph-px         11
   :compound-title-px      11
   :caption-strip-px       28
   :caption-text-px        11

   ;; ── state-tag pills (rf2-m1b88) ──────────────────────────────
   :tag-pill-height        12
   :tag-pill-pad-x         5
   :tag-pill-px            8
   :tag-pill-gap           3
   :tag-pill-row-gap       4

   ;; ── dot-grid background (rf2-m4nj4) ──────────────────────────
   :dot-grid-spacing-px    16
   :dot-grid-radius-px     1.0
   :dot-grid-alpha         0.06

   ;; ── motion (rf2-xfx6l) — see theme/tokens/motion for the seam ─
   :pulse-stroke-width-add 1.0})
