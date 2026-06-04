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
  single-chart-display case (Xray's machines tab on a wide
  monitor). Hosts pick one via the chart's `:density` prop. The
  three maps share the SAME key set (asserted by
  `visual-constants-cljs-test`); a key in one is a key in all.

  rf2-cd053 / rf2-gg7ws — `chart-regular`'s edge-label font size walked
  up from the previous spec/007-UX-IA refused-floor (9) to a chart-
  appropriate 11 per the 2026-05-20 visual-quality lift. The refused-
  floor was set for dense data-grid surfaces; applying it to a chart
  that competes with xstate-stately's typography was a category error.
  `chart-compact` deliberately walks back to 9 since the compact density
  IS the dense-grid surface the original floor existed for; `chart-cosy`
  walks up to 13. (The state-node label rides `:state-title-px` under
  the rf2-az6e2 structured grammar; the old `:state-label-px` key it
  superseded was removed rf2-dt5b1 — see below.)

  rf2-dt5b1 — removed seven density-map keys no renderer ever read:
  `:state-label-px` (superseded by `:state-title-px`), `:compound-pad-x`
  / `:compound-pad-y` / `:compound-stroke-dash` / `:compound-title-px`
  (the compound chrome reads the `:container-*` keys), `:dot-grid-alpha`
  (the dot grid is xyflow's `<Background>` `:gap`/`:size`, no alpha),
  and `:edge-label-backplate-opacity` (the edge-label backplate paints
  the opaque `:event-chip-bg` theme token, no separate opacity). Added
  `:arrow-width` / `:arrow-width-quiet` / `:arrow-width-entry` so the
  arrowhead size rides the density alongside the stroke (the projector
  baked literal 10 / 18 / 12 before, ignoring `:density`).

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
  numbers and had DRIFTED from `chart-regular`. The reconciliation
  moves `chart-regular` to the SHIPPED renderer numbers (so wiring
  `:density` introduces no visual regression at the default) and adds
  `:compound-radius` — a render constant the renderer hardcoded but
  `visual-constants` did not yet carry. The renderer now READS every
  one of these off the resolved density map (threaded through the
  projector's per-node/per-edge `:data`); switching `:density`
  changes the render for real and the chart root emits `data-density`.

  rf2-so5b0 retired the `:tag-pill-*` family along with the visible
  state-tag pill row; rf2-a2b55 reinstates BOTH the family AND the row,
  positioned BELOW the state name (Stately graph view convention) and
  adds an `:action-pill-*` family for the entry-action + edge-action
  pills (`+ <action-name>`) the same convention uses for transition
  actions. User-declared `:tags` (Spec 005) still surface on the
  state-node's `:data-tags` + `:title` attrs for host-introspection +
  hover; the visible pill row is the eye-scan surface that pairs the
  tag set with each state, the way Stately's graph view paints them."
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
    :edge-label-px            — edge label font-size
                                (rf2-gg7ws lift — regular: 11)
    :arrow-width              — PRIMARY arrowhead width/height in px,
                                on the `__out` (event→target) half of an
                                events-as-nodes route (rf2-dt5b1 — now
                                rides the density so the head scales with
                                the stroke instead of a baked literal)
    :arrow-width-quiet        — QUIET arrowhead width/height on the
                                `__in` (source→event) half — smaller so
                                the primary head reads as the route's
                                terminus and the pair reads as ONE
                                transition (rf2-dt5b1)
    :arrow-width-entry        — initial-marker entry-edge arrowhead
                                width/height (rf2-dt5b1)
    :compound-radius          — compound container corner radius
                                (rf2-k647w — distinct from the
                                state-node `:corner-radius` lock; the
                                compound chrome reads as a looser box)
    :tag-pill-height          — state-tag pill height (rf2-m1b88;
                                rf2-a2b55 restored under state-name)
    :tag-pill-pad-x           — state-tag pill horizontal padding
    :tag-pill-px              — state-tag pill text font-size
    :tag-pill-radius          — state-tag pill corner radius
    :tag-pill-gap             — horizontal gap between adjacent pills
    :tag-pill-row-gap         — vertical gap from pill row to state
                                label (positive ⇒ pills sit BELOW name)
    :action-pill-height       — entry/exit + edge action pill height
                                (rf2-a2b55 — `+ <action>` Stately
                                graph view convention; distinct from
                                tag pills so action chrome can scale
                                independently)
    :action-pill-pad-x        — action pill horizontal padding
    :action-pill-px           — action pill text font-size
    :action-pill-radius       — action pill corner radius
    :action-pill-gap          — gap between adjacent action pills
    :action-pill-row-gap      — gap from action pill row to its
                                anchor (state label / edge event line)
    :dot-grid-spacing-px      — dot-grid background pattern spacing
                                (rf2-m4nj4 — 16px)
    :dot-grid-radius-px       — single dot radius"
  {;; ── geometry ─────────────────────────────────────────────────
   :corner-radius          6
   :stroke-width           1.5
   :stroke-width-emphasis  2.5
   :compound-radius        10

   ;; ── typography (rf2-gg7ws — chart-regular floor 13/11) ───────
   :edge-label-px          11

   ;; ── edge arrowheads (rf2-dt5b1 — ride the density so the head
   ;;    scales with the stroke; quiet `__in` < primary `__out`) ───
   :arrow-width            18
   :arrow-width-quiet      10
   :arrow-width-entry      12

   ;; ── state-tag pills (rf2-m1b88; rf2-a2b55 restored — sit BELOW
   ;;    the state name per Stately graph view convention) ─────────
   :tag-pill-height        16
   :tag-pill-pad-x         6
   :tag-pill-px            9
   :tag-pill-radius        8
   :tag-pill-gap           3
   :tag-pill-row-gap       4

   ;; ── action pills (rf2-a2b55 — entry/exit + edge actions render
   ;;    as `+ <action>` pills per Stately graph view convention) ──
   :action-pill-height     16
   :action-pill-pad-x      6
   :action-pill-px         9
   :action-pill-radius     6
   :action-pill-gap        4
   :action-pill-row-gap    4

   ;; ── structured topology grammar (rf2-az6e2) ──────────────────
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
   :event-chip-radius      5
   :event-chip-px          11    ;; event label / guard font-size
   :event-chip-action-px   9     ;; action-row font-size
   ;; Pseudo-state markers (initial dot, history H/H* hook).
   :pseudo-size            12
   :pseudo-radius          6
   :pseudo-px              8
   ;; Root chrome (title strip + context panel).
   :root-title-height      30
   :root-title-px          14
   :root-context-pad       8

   ;; ── dot-grid background (rf2-m4nj4) ──────────────────────────
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
  spacing); corner-radius is unchanged because the rf2-g6cig lock
  applies across every density (a wireframe-looking thumbnail is
  still wrong).

  Shares the SAME key set as `chart-regular` (asserted by
  visual-constants-cljs-test rf2-32gw5)."
  {;; ── geometry (~25% tighter) ──────────────────────────────────
   :corner-radius          6           ;; rf2-g6cig lock — same in every density
   :stroke-width           1.0
   :stroke-width-emphasis  2.0
   :compound-radius        8           ;; ~25% tighter than regular's 10

   ;; ── typography (rf2-gg7ws refused-floor revisited for thumbnails)
   :edge-label-px          9

   ;; ── edge arrowheads (rf2-dt5b1 — ~25% tighter than regular) ──
   :arrow-width            14
   :arrow-width-quiet      8
   :arrow-width-entry      10

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
   :action-pill-gap        3
   :action-pill-row-gap    3

   ;; ── structured topology grammar (rf2-az6e2 — ~25% tighter) ───
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
   :event-chip-radius      4
   :event-chip-px          9
   :event-chip-action-px   7
   :pseudo-size            10
   :pseudo-radius          5
   :pseudo-px              7
   :root-title-height      26
   :root-title-px          12
   :root-context-pad       6

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
   :compound-radius        12          ;; ~25% looser than regular's 10

   ;; ── typography (walked up one notch from the regular floor) ──
   :edge-label-px          13

   ;; ── edge arrowheads (rf2-dt5b1 — ~25% looser than regular) ───
   :arrow-width            22
   :arrow-width-quiet      12
   :arrow-width-entry      15

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
   :action-pill-gap        5
   :action-pill-row-gap    5

   ;; ── structured topology grammar (rf2-az6e2 — ~25% looser) ────
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
   :event-chip-radius      6
   :event-chip-px          13
   :event-chip-action-px   11
   :pseudo-size            14
   :pseudo-radius          7
   :pseudo-px              9
   :root-title-height      34
   :root-title-px          16
   :root-context-pad       10

   ;; ── dot-grid background ──────────────────────────────────────
   :dot-grid-spacing-px    20
   :dot-grid-radius-px     1.15})

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
                     :where     'machines-viz/chart-for-density
                     :recovery  :no-recovery
                     :reason    (str "unknown chart density: " (pr-str density)
                                     ". Expected one of " (pr-str densities) ".")
                     :density   density
                     :expected  densities}))))
