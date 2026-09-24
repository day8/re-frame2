(ns day8.re-frame2-machines-viz.visual-constants
  "Chart visual constants — single source for the geometry + typography
  + motion durations that shape the MachineChart's character. One map
  keeps the chart's visual character legible at a glance.

  The chart ships THREE density variants: `chart-compact`,
  `chart-regular`, `chart-cosy`. `regular` is the load-bearing chart
  floor and the default every consumer gets when `:density` is
  unspecified or `nil`. `compact` shrinks geometry + typography
  proportionally for grid layouts (Story's 50-chart panel grid); `cosy`
  widens both for the single-chart-display case (Xray's machines tab on
  a wide monitor). Hosts pick one via the chart's `:density` prop. The
  three maps share the SAME key set (asserted by
  `visual-constants-cljs-test`); a key in one is a key in all.

  `chart-regular`'s edge-label font size is a chart-appropriate 11,
  above the spec/007-UX-IA refused-floor (9). That floor is set for
  dense data-grid surfaces; applying it to a chart that competes with
  xstate-stately's typography would be a category error.
  `chart-compact` deliberately sits at 9, since the compact density IS
  the dense-grid surface the floor exists for; `chart-cosy` sits at 13.
  The state-node label rides `:state-title-px`.

  A key belongs in these maps only when a renderer reads it: a key that
  tunes no pixel implies, across three maps, a tuning surface that does
  not exist. The arrowhead sizes (`:arrow-width` / `:arrow-width-quiet`
  / `:arrow-width-entry`) ride the density alongside the stroke.

  Corner-radius is locked at 6px across every density. The React Flow
  default (8) reads as 'product chrome'; brutalist (0) reads as
  'wireframe'; 6 is the sweet spot — soft enough to feel finished,
  sharp enough to read as 'data, not product'. Density does not unlock
  this.

  There is no heartbeat-pulse animation. The active state's static
  affordance (cyan tint + emphasised stroke) carries the 'currently
  here' signal without a continuous loop; the transition glow on
  event-fire is the moment-of-cause cue.

  This ns is the SINGLE SOURCE of the chart's render
  geometry/typography. The xyflow `MachineChart` (`chart.nodes` /
  `chart.edges` / `chart.cljs`) READS every one of these off the
  resolved density map (threaded through the projector's
  per-node/per-edge `:data`), so switching `:density` changes the
  render and the chart root emits `data-density`.

  The `:tag-pill-*` family sizes the visible state-tag pill row,
  positioned BELOW the state name (Stately graph view convention), and
  the `:action-pill-*` family sizes the entry-action + edge-action pills
  (`+ <action-name>`) the same convention uses for transition actions.
  User-declared `:tags` (Spec 005) also surface on the state-node's
  `:data-tags` + `:title` attrs for host-introspection + hover; the
  visible pill row is the eye-scan surface that pairs the tag set with
  each state, the way Stately's graph view paints them."
  {:no-doc true})

(def chart-regular
  "Chart visual constants — REGULAR density (the default).

  Keys:

    :corner-radius            — node rounded-corner radius in px
                                (locked: 6 across every density)
    :stroke-width             — default node + edge stroke width
    :stroke-width-emphasis    — emphasised node/edge stroke width
                                (active / focused-event lens)
    :edge-label-px            — edge label font-size (regular: 11)
    :arrow-width              — PRIMARY arrowhead width/height in px,
                                on the `__out` (event→target) half of an
                                events-as-nodes route (rides the density
                                so the head scales with the stroke)
    :arrow-width-quiet        — QUIET arrowhead width/height on the
                                `__in` (source→event) half — smaller so
                                the primary head reads as the route's
                                terminus and the pair reads as ONE
                                transition
    :arrow-width-entry        — initial-marker entry-edge arrowhead
                                width/height
    :compound-radius          — compound container corner radius
                                (distinct from the state-node
                                `:corner-radius` lock; the compound
                                chrome reads as a looser box)
    :tag-pill-height          — state-tag pill height (the pill row
                                sits under the state name)
    :tag-pill-pad-x           — state-tag pill horizontal padding
    :tag-pill-px              — state-tag pill text font-size
    :tag-pill-radius          — state-tag pill corner radius
    :tag-pill-gap             — horizontal gap between adjacent pills
    :tag-pill-row-gap         — vertical gap from pill row to state
                                label (positive ⇒ pills sit BELOW name)
    :action-pill-height       — entry/exit + edge action pill height
                                (`+ <action>` Stately
                                graph view convention; distinct from
                                tag pills so action chrome can scale
                                independently)
    :action-pill-pad-x        — action pill horizontal padding
    :action-pill-px           — action pill text font-size
    :action-pill-radius       — action pill corner radius
    :action-pill-row-gap      — gap from action pill row to its
                                anchor (state label / edge event line)
    :dot-grid-spacing-px      — dot-grid background pattern spacing
                                (16px)
    :dot-grid-radius-px       — single dot radius"
  {;; ── geometry ─────────────────────────────────────────────────
   :corner-radius          6
   :stroke-width           1.5
   :stroke-width-emphasis  2.5
   :compound-radius        10

   ;; ── typography (chart-regular floor 13/11) ───────────────────
   :edge-label-px          11

   ;; ── edge arrowheads (ride the density so the head scales with
   ;;    the stroke; quiet `__in` < entry < primary `__out`). Primary
   ;;    is sized toward Stately's small/thin heads; quiet + entry sit
   ;;    below it to keep the ordering invariant. ─────────────────────
   :arrow-width            12
   :arrow-width-quiet      8
   :arrow-width-entry      10

   ;; ── state-tag pills (sit BELOW the state name per Stately graph
   ;;    view convention) ───────────────────────────────────────────
   :tag-pill-height        16
   :tag-pill-pad-x         6
   :tag-pill-px            9
   :tag-pill-radius        8
   :tag-pill-gap           3
   :tag-pill-row-gap       4

   ;; ── action pills (entry/exit + edge actions render
   ;;    as `+ <action>` pills per Stately graph view convention) ──
   :action-pill-height     16
   :action-pill-pad-x      6
   :action-pill-px         9
   :action-pill-radius     6
   :action-pill-row-gap    4

   ;; ── structured topology grammar ──────────────────────────────
   ;; State title/body box geometry.
   :state-title-height     24    ;; full-width title strip height
   :state-title-pad-x      10
   :state-title-px         13    ;; title font-size (sans, structure-first)
   :state-body-pad-x       10
   :state-body-pad-y       8
   :state-body-gap         4     ;; vertical gap between body rows
   :state-divider-width    1     ;; title/body hairline divider
   :state-shadow-blur      6     ;; resting drop-shadow blur radius
   ;; Compound container chrome.
   :container-title-height 26
   :container-title-pad-x  12
   :container-body-pad     14    ;; inset around children below the strip
   :container-divider-width 1
   ;; Parallel-region chrome.
   :region-title-height    24
   :region-title-pad-x     10
   ;; Action-section caption ("Entry actions" / "Exit actions").
   :action-caption-px      8
   :action-caption-gap     2
   ;; Event route chip.
   :event-chip-min-w       92
   :event-chip-min-h       32
   :event-chip-pad-x       10
   :event-chip-pad-y       4
   :event-chip-radius      16    ;; capsule pill — ≈ half :event-chip-min-h
                                 ;; (32) so the chip reads as a Stately-style
                                 ;; rounded pill, not a near-rectangle
   :event-chip-px          11    ;; event label / guard font-size
   :event-chip-action-px   9     ;; action-row font-size
   ;; Pseudo-state markers (initial dot, history H/H* hook).
   :pseudo-size            12
   :pseudo-radius          6
   :pseudo-px              8
   ;; ── dot-grid background ──────────────────────────────────────
   :dot-grid-spacing-px    16
   :dot-grid-radius-px     1.0})

(def chart-compact
  "Chart visual constants — COMPACT density.

  Smaller nodes, smaller type, tighter gaps. Targets the 50-chart
  Story grid where each chart is a thumbnail and the user's eye
  scans the grid for shape rather than reading individual labels.

  Walks the typography back to the spec/007-UX-IA refused-floor
  (11 / 9) — the refused-floor was set for dense data-grid surfaces,
  and the compact density IS the dense-grid surface it existed for.

  Geometry is pulled in by ~25% (paddings, pill height, dot-grid
  spacing); corner-radius stays 6 because the corner-radius lock
  applies across every density (a wireframe-looking thumbnail is
  still wrong).

  Shares the SAME key set as `chart-regular` (asserted by
  visual-constants-cljs-test)."
  {;; ── geometry (~25% tighter) ──────────────────────────────────
   :corner-radius          6           ;; locked — same in every density
   :stroke-width           1.0
   :stroke-width-emphasis  2.0
   :compound-radius        8           ;; ~25% tighter than regular's 10

   ;; ── typography (the refused-floor, for thumbnails) ───────────
   :edge-label-px          9

   ;; ── edge arrowheads (tighter than regular; primary sized
   ;;    toward Stately's small/thin heads) ─────────────────────────
   :arrow-width            10
   :arrow-width-quiet      7
   :arrow-width-entry      9

   ;; ── state-tag pills (tighter than the regular 16/6/9/8) ──────
   :tag-pill-height        13
   :tag-pill-pad-x         5
   :tag-pill-px            7
   :tag-pill-radius        6
   :tag-pill-gap           2
   :tag-pill-row-gap       3

   ;; ── action pills (~25% tighter than the regular 16/6/9/6) ────
   :action-pill-height     13
   :action-pill-pad-x      5
   :action-pill-px         7
   :action-pill-radius     5
   :action-pill-row-gap    3

   ;; ── structured topology grammar (~25% tighter) ───────────────
   :state-title-height     20
   :state-title-pad-x      8
   :state-title-px         11
   :state-body-pad-x       8
   :state-body-pad-y       6
   :state-body-gap         3
   :state-divider-width    1
   :state-shadow-blur      4
   :container-title-height 22
   :container-title-pad-x  10
   :container-body-pad     11
   :container-divider-width 1
   :region-title-height    20
   :region-title-pad-x     8
   :action-caption-px      7
   :action-caption-gap     2
   :event-chip-min-w       76
   :event-chip-min-h       26
   :event-chip-pad-x       8
   :event-chip-pad-y       3
   :event-chip-radius      13    ;; capsule pill — ≈ half compact :event-chip-min-h (26)
   :event-chip-px          9
   :event-chip-action-px   7
   :pseudo-size            10
   :pseudo-radius          5
   :pseudo-px              7
   ;; ── dot-grid background ──────────────────────────────────────
   :dot-grid-spacing-px    12
   :dot-grid-radius-px     0.85})

(def chart-cosy
  "Chart visual constants — COSY density.

  Larger nodes, larger type, more breathing room. Targets the
  single-chart-display case — Xray's machines tab on a wide monitor
  or a presentation-mode standalone viewer. The user is reading the
  chart, not scanning a grid; labels are payload, not decoration.

  Walks the typography up to 15 / 13. Geometry widens by ~25%
  (paddings, pill height, dot-grid spacing). Corner-radius stays 6
  per the corner-radius lock — the chart's visual character must read
  consistently across densities; only quantity scales, not identity.

  Shares the SAME key set as `chart-regular` (asserted by
  visual-constants-cljs-test)."
  {;; ── geometry (~25% looser) ───────────────────────────────────
   :corner-radius          6           ;; locked — same in every density
   :stroke-width           1.75
   :stroke-width-emphasis  3.0
   :compound-radius        12          ;; ~25% looser than regular's 10

   ;; ── typography (walked up one notch from the regular floor) ──
   :edge-label-px          13

   ;; ── edge arrowheads (looser than regular; primary sized
   ;;    toward Stately's small/thin heads) ─────────────────────────
   :arrow-width            14
   :arrow-width-quiet      10
   :arrow-width-entry      12

   ;; ── state-tag pills (looser than the regular 16/6/9/8) ───────
   :tag-pill-height        19
   :tag-pill-pad-x         7
   :tag-pill-px            11
   :tag-pill-radius        10
   :tag-pill-gap           4
   :tag-pill-row-gap       5

   ;; ── action pills (~25% looser than the regular 16/6/9/6) ─────
   :action-pill-height     19
   :action-pill-pad-x      7
   :action-pill-px         11
   :action-pill-radius     8
   :action-pill-row-gap    5

   ;; ── structured topology grammar (~25% looser) ────────────────
   :state-title-height     28
   :state-title-pad-x      12
   :state-title-px         15
   :state-body-pad-x       12
   :state-body-pad-y       10
   :state-body-gap         5
   :state-divider-width    1
   :state-shadow-blur      8
   :container-title-height 30
   :container-title-pad-x  14
   :container-body-pad     18
   :container-divider-width 1
   :region-title-height    28
   :region-title-pad-x     12
   :action-caption-px      9
   :action-caption-gap     3
   :event-chip-min-w       108
   :event-chip-min-h       38
   :event-chip-pad-x       12
   :event-chip-pad-y       5
   :event-chip-radius      18    ;; capsule pill — ≈ half cosy :event-chip-min-h (38)
   :event-chip-px          13
   :event-chip-action-px   11
   :pseudo-size            14
   :pseudo-radius          7
   :pseudo-px              9
   ;; ── dot-grid background ──────────────────────────────────────
   :dot-grid-spacing-px    20
   :dot-grid-radius-px     1.15})

(def chart
  "Default chart visual constants — alias for `chart-regular`.

  Direct consumers may reference `vc/chart` for the regular-density
  map. The `MachineChart` component's `:density`
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
                runtime fallback (hosts pick from the closed set or
                the chart rejects).

  Pure fn — JVM-runnable. The MachineChart component calls this once
  per render, then threads the resolved map through every helper."
  [density]
  (cond
    (nil? density) chart-regular
    (contains? density->chart-map density) (get density->chart-map density)
    :else
    (throw (ex-info ":rf.error/machines-viz-unknown-chart-density"
                    {:rf.error/id :rf.error/machines-viz-unknown-chart-density
                     :where     'machines-viz/chart-for-density
                     :recovery  :no-recovery
                     :reason    (str "unknown chart density: " (pr-str density)
                                     ". Expected one of " (pr-str densities) ".")
                     :density   density
                     :expected  densities}))))
