(ns day8.re-frame2-causa.theme.tokens
  "Shared design tokens for the Causa shell + every panel view.

  ## Single source of truth (rf2-rclvn)

  Phase 1 / Phase 2 / Phase 3 panel views each carried a private copy
  of the dark-theme palette plus the `mono-stack` + `sans-stack` font
  defs. Drift had already started — `:orange` was unique to the
  performance panel even though `spec/007-UX-IA.md` §Colour system
  catalogues it as part of the canonical perf scale. One source of
  truth — this ns — removes the duplication and makes the v1.0
  CSS-variable migration a one-file change.

  Per `tools/causa/spec/007-UX-IA.md` §Dark theme tokens. Phase 1 uses
  inline styles so the foundation ships without a CSS asset pipeline;
  the v1.0 styling pass replaces these with CSS variables.

  ## How panels consume this

      (:require [day8.re-frame2-causa.theme.tokens
                 :refer [tokens mono-stack sans-stack]])

  …then `(:bg-1 tokens)` / `mono-stack` resolve as if locally defined.
  The `:refer` form keeps every existing use-site working without
  rename churn.

  ## What lives here

  - **`tokens`** — the dark-theme palette. Keys are stable token
    names; values are hex strings.
  - **`mono-stack`** — the JetBrains Mono font stack for code /
    EDN / mono-column rendering.
  - **`sans-stack`** — the Inter font stack for chrome / labels /
    prose.
  - **`type-scale`** — typography sizes (px strings) + base
    line-height. The shell's default density (rf2-pcitk) — denser
    than the spec's cosy baseline, closer to compact, because Causa
    is an info-dense dev tool. One-knob tuning lives here; raise the
    sizes one number to bring the shell back to spec-cosy.
  - **`layout`** — chrome dimensions (sidebar width, etc.) consumed
    by the shell. Single source for the density knob.

  ## What does not live here

  Semantic-mapping tables that emit token *keywords* (e.g. an outcome
  → `:green` table) live in each panel's `*_helpers.cljc` so the
  pure-data side stays JVM-portable. The hex resolution happens here."
  {:no-doc true})

;; ---- per-theme palettes -------------------------------------------------

(def dark-palette
  "Dark-theme colour tokens lifted from `spec/007-UX-IA.md` §Dark theme
  tokens. The default Causa palette; `tokens` is an alias of this map
  so the 357 inline `(:bg-1 tokens)` call sites keep resolving without
  a runtime switch (the CSS-variable migration is the v1.0 styling
  pass)."
  {;; ── surfaces ──
   :bg-0           "#0E0F12"
   :bg-1           "#15171B"
   :bg-2           "#1B1E24"
   :bg-3           "#232730"
   :bg-active      "#2A2F3D"

   ;; ── borders ──
   :border-subtle  "#232730"
   :border-default "#2F3441"

   ;; ── text ──
   :text-primary   "#E8EAF0"
   :text-secondary "#A8AEC0"
   :text-tertiary  "#6B7080"

   ;; ── accents + semantic ──
   :accent-violet  "#7C5CFF"
   :cyan           "#43C3D0"
   :green          "#4ADE80"
   :yellow         "#FBBF24"
   :orange         "#FB923C"
   :red            "#F87171"
   :magenta        "#E879F9"

   ;; ── deep variants (rf2-5kfxe.4) ──
   ;; Darker variant of `:red` used as a danger-button background. The
   ;; default `:red` is the standard text-on-bg accent (high lightness
   ;; for readability over the dark canvas); a button surface that
   ;; FILLS the rectangle wants a deeper hue so white text on red
   ;; stays AA-grade.
   :red-deep       "#a83a3a"

   ;; Universal white — readable on the violet accent + the deep
   ;; reds. Catalogued here so the few "white text on coloured
   ;; surface" spots (primary / danger buttons) flow through tokens
   ;; like every other colour.
   :white          "#ffffff"})

(def light-palette
  "Light-theme colour tokens per `spec/007-UX-IA.md` §Colour system —
  'Light theme inverts lightness (bg-0 #FAFBFC, bg-1 #F1F3F6, bg-2
  #FFFFFF); accents darken slightly to maintain contrast'. (rf2-5kfxe.6)

  Surfaces invert (bg-0 is the *lightest* deepest-canvas tone, bg-3
  the most saturated chrome); text inverts so primary is near-black;
  borders subtle in greys; accents darken ~15-20% to maintain AA
  contrast on the white canvas.

  Consumed via the per-theme CSS custom-property block emitted by
  `theme/global-styles/themes-css`. The class toggle
  (`rf-causa-theme-light` on the shell root, written by
  `settings/effects/apply-theme!`) flips which block is in scope. The
  357 inline-style call sites that read `(:bg-1 tokens)` continue to
  resolve against the dark palette — the light-theme surface is the
  CSS-variable layer until the v1.0 sweep migrates inline styles
  through to it."
  {;; ── surfaces ── (lighter as the depth increases — bg-2 is the
   ;; brightest 'top' canvas, bg-0 the gentlest 'recess')
   :bg-0           "#FAFBFC"
   :bg-1           "#F1F3F6"
   :bg-2           "#FFFFFF"
   :bg-3           "#E6E9EE"
   :bg-active      "#DCE0E7"

   ;; ── borders ── (lighter mid-greys; the gap between subtle and
   ;; default mirrors the dark palette's discrimination)
   :border-subtle  "#E6E9EE"
   :border-default "#CFD4DC"

   ;; ── text ── (near-black down to mid-grey)
   :text-primary   "#15171B"
   :text-secondary "#4B5160"
   :text-tertiary  "#8B92A1"

   ;; ── accents + semantic ── (each ~15-20% darker than the dark
   ;; palette to maintain ≥4.5:1 contrast on the white canvas)
   :accent-violet  "#5538D8"
   :cyan           "#2A8B96"
   :green          "#2F9E5C"
   :yellow         "#B07A05"
   :orange         "#C2570F"
   :red            "#C84444"
   :magenta        "#B146C2"

   ;; ── deep variants ──
   ;; On the light canvas the danger button stays close to the
   ;; semantic red — depth is signalled by saturation, not lightness.
   :red-deep       "#9A3030"

   :white          "#ffffff"})

(def themes
  "Map of theme-name → palette map. The shell toggles
  `rf-causa-theme-<name>` on its root via
  `settings/effects/apply-theme!`; `theme/global-styles/themes-css`
  emits one CSS custom-property block per theme keyed by the matching
  class selector.

  Adding a new theme is one entry here + one default in
  `settings/effects/apply-theme!`'s drop-class list (so the toggle
  stays exclusive)."
  {:dark  dark-palette
   :light light-palette})

(def tokens
  "Backward-compatible alias for the dark palette. The 357 inline-style
  call sites that read `(:bg-1 tokens)` keep resolving against the
  dark palette; the light theme is layered on top via CSS custom
  properties (see `theme/global-styles/themes-css`). The v1.0 styling
  pass migrates each call site through to the CSS-variable surface so
  the inline-style indirection collapses."
  dark-palette)

(def mono-stack
  "JetBrains Mono stack per spec/007-UX-IA.md §Typography. Used by
  every panel's mono column (event vectors, EDN values, hashes,
  paths)."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def sans-stack
  "Inter stack per spec/007-UX-IA.md §Typography. Used by chrome,
  labels, prose, and every non-mono surface in the panels."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")

(def display-stack
  "Fraunces stack — Causa's display face (rf2-5kfxe.9).

  Fraunces is a variable serif (open-source, by Undercase Type) with
  optical-size + SOFT + WONK axes designed to be characterful at
  large sizes. Deliberately *not* another grotesque sans — the
  frontend-design rubric flags 'Inter at every size' as a generic
  AI-aesthetic. The body chrome stays Inter; only L4 panel <h1>s
  reach for this face so the visual hierarchy is unmistakeable.

  Fallback chain: `ui-serif` is the modern serif system pointer
  (Safari/Chrome resolve it to the platform's native serif —
  Georgia on macOS, Cambria on Windows). Then the explicit
  Georgia/Cambria/Times so older browsers + locked-down
  enterprise envs still land on *some* serif rather than falling
  through to a sans.

  ~30KB WOFF2 (variable, optical-size axis 9-144). The shell auto-
  injects `local()`-only `@font-face` declarations (see
  `theme/global-styles/font-faces-css`) so an OS-installed Fraunces
  resolves automatically; absent that, the fallback chain above takes
  over with zero HTTP fetch. Consuming projects that want web-hosted
  Fraunces inject their own `@font-face` rules with `url()` entries
  pointing at vendored / CDN-hosted WOFF2s — CSS layers candidates
  by family + weight so the host-side rules compose with the
  `local()` defaults."
  "Fraunces, ui-serif, Georgia, Cambria, Times, serif")

(def font-size-var-name
  "CSS custom-property name the whole type-scale interpolates through
  (rf2-n8i2c). `theme/global-styles/motion-css` publishes a default
  value of `13px` on `:root`; host pages, the settings panel's
  density slider, or DevTools overrides can swap the value and the
  entire shell's type rescales in lockstep.

  Modelled on TanStack Query Devtools' `--tsqd-font-size` knob — one
  variable, every size derived via `calc()` with relative multipliers.
  The 357 inline-style call sites that read `(:body type-scale)`
  continue to resolve to a CSS string; the browser does the
  multiplication at paint time, so changing `:root { --rf-causa-font-
  size: 16px }` rescales every typographic surface ~1.23x without a
  code change."
  "--rf-causa-font-size")

(def font-size-default
  "Default value of `--rf-causa-font-size` published on `:root` by
  `theme/global-styles/motion-css`. This is the historical Causa
  baseline (`:body` = 13px); the per-key multipliers below are
  expressed RELATIVE to it (e.g. `:caption` = 0.85 → ~11px at the
  default knob).

  Catalogued here rather than only in the CSS string so the JVM
  side has the literal default for tests / inspection without
  parsing a calc() expression."
  "13px")

(def type-scale-multipliers
  "Pure-data multipliers used to derive each `type-scale` entry from
  the `--rf-causa-font-size` knob. Catalogued separately from the
  emitted calc-strings so tests can assert the relationship without
  re-parsing a CSS expression.

  Each multiplier expresses the entry's size as a fraction of the
  CSS-variable default. Anchored on `:body = 1.0`:

  - `:display`     1.077  — panel titles (~14px at the 13px default)
  - `:body`        1.000  — default UI text (the anchor)
  - `:body-tight`  0.923  — sidebar entries, header chrome (~12px)
  - `:mono-body`   0.923  — code, EDN (~12px)
  - `:caption`     0.846  — hints, secondary labels (~11px)
  - `:micro`       0.769  — badges, tabs (~10px; spec's refused floor)

  Multipliers chosen so the emitted calc-strings round to the same
  pixel values the previous fixed-px table shipped — no perceptual
  shift at the default knob; downstream rescales are uniform."
  {:display     1.077
   :body        1.0
   :body-tight  0.923
   :mono-body   0.923
   :caption     0.846
   :micro       0.769})

(defn font-size-css
  "Build the canonical `calc(var(--rf-causa-font-size, 13px) * N)`
  CSS string each `type-scale` entry uses. One knob — change `:root
  { --rf-causa-font-size: 16px }` and every derived size rescales
  in lockstep.

  Pure data → string; JVM-portable so the .cljc test surface
  exercises the calc shape on the JVM runner."
  [multiplier]
  (str "calc(var(" font-size-var-name ", " font-size-default ") * "
       multiplier ")"))

(def type-scale
  "Causa shell typography sizes (rf2-pcitk + rf2-n8i2c font-size-var
  migration).

  Causa is an info-dense dev surface. Mike's UX session against the
  testbed flagged the cosy baseline (body 14 / mono 13 / line-height
  1.5) as too LARGE — the eye has to travel further than the data
  warrants. This scale runs ~1px below cosy across the board and
  tightens line-height to 1.35, which is the readability floor for
  monospaced data dumps.

  ## One knob, whole scale (rf2-n8i2c)

  Every size below resolves through the `--rf-causa-font-size` CSS
  custom property (default `13px`, published on `:root` by
  `theme/global-styles/motion-css`). Each entry is
  `calc(var(--rf-causa-font-size, 13px) * <multiplier>)` where the
  multiplier expresses the entry's RELATIVE size — `:body` is the
  1.0 anchor, `:display` is 1.077, `:caption` is 0.846, and so on.

  Modelled on TanStack Query Devtools' `--tsqd-font-size` knob: one
  variable scales the entire UI. Override `--rf-causa-font-size` at
  `:root` (or under a `.rf-causa-density-compact` / `-comfy` class
  toggle once wired) and every typographic surface rescales together
  without a single code change.

  spec/007-UX-IA.md §Typography catalogues the cosy baseline and a
  ±1px density knob (compact/cosy/comfy). The runtime density
  toggle plumb-through lands as a follow-on; this commit ships the
  CSS-variable foundation it builds on.

  Values are CSS strings so call sites can drop them straight into
  inline `:style` maps. The browser resolves `calc(var(--…) * N)`
  at paint time, so the knob takes effect on the next style flush
  without a re-render."
  {;; Headings + prose
   :display      (font-size-css (:display     type-scale-multipliers))
   :body         (font-size-css (:body        type-scale-multipliers))
   :body-tight   (font-size-css (:body-tight  type-scale-multipliers))
   :mono-body    (font-size-css (:mono-body   type-scale-multipliers))
   :caption      (font-size-css (:caption     type-scale-multipliers))
   :micro        (font-size-css (:micro       type-scale-multipliers))
   ;; Vertical rhythm — unitless ratios, unchanged by the font-size
   ;; knob (line-height naturally scales with the resolved font-size).
   :line-height-tight 1.35   ; was 1.5 — denser blocks
   :line-height-mono  1.4    ; mono needs a touch more leading for ascender clearance
   })

(def layout
  "Causa shell layout dimensions (rf2-pcitk + rf2-g9pee). Single source
  for the chrome's fixed-height layer measurements.

  The 4-layer chrome is L1 ribbon (top-strip) + L2 event list + L3
  tab bar + L4 detail panel — no bottom rail, no sidebar (both dropped
  in earlier Causa redesigns and the now-unused `:sidebar-width` /
  `:bottom-rail-height` tokens were retired in Round-3 rf2-g9pee)."
  {:top-strip-height "56px"})

;; ---- motion (rf2-5kfxe.5) ----------------------------------------------

(def motion
  "Motion-axis tokens (rf2-5kfxe.5). Every Causa animation interpolates
  its duration through `--rf-causa-motion-scale`, a CSS custom
  property set on `:root` by `theme/global-styles`. A single media-
  query rule overrides the property to ~0 under `prefers-reduced-
  motion: reduce`, collapsing every downstream animation to its end
  state in a single frame.

  - `:scale-var-name`   — CSS variable name; consumers write
                          `\"var(\" :scale-var-name \", 1)\"` in their
                          inline `animation-duration` `calc(...)`
                          expressions so the seam stays one
                          identifier.
  - `:flash-duration-ms` — the canonical 400ms diff-flash duration
                          (rf2-5kfxe.2). Catalogued here so the
                          renderer can read it without forking the
                          number.
  - `:fade-duration-ms`  — the canonical 180ms tab cross-fade
                          duration (rf2-5kfxe.3).

  The CSS keyframes themselves live in `theme/global-styles/motion-
  css`; this map is the symbolic surface for consumers that need to
  reason about durations / the seam variable in cljs-land."
  {:scale-var-name    "--rf-causa-motion-scale"
   :flash-duration-ms 400
   :fade-duration-ms  180})

(defn duration-css
  "Build the canonical `calc(<ms>ms * var(--rf-causa-motion-scale, 1))`
  CSS string a consumer passes as `animation-duration`. The
  `prefers-reduced-motion: reduce` seam in `theme/global-styles`
  collapses the var to a vanishingly small value, so motion-using
  surfaces that build their duration through this helper honour
  reduced-motion without any per-component branching.

  Pure data → string; JVM-portable."
  [ms]
  (str "calc(" ms "ms * var(" (:scale-var-name motion) ", 1))"))

;; ---- L4 panel domain colours (rf2-5kfxe.8) -----------------------------

(def panel-domain->token
  "Pure semantic map from L4 tab keyword → token keyword used as the
  panel's domain colour. Each panel renders a 3px left-border on its
  `<h1>` in this colour so panels are distinguishable at a glance
  without restructuring the header chrome.

  Choices mirror the existing semantic colour usage across panels:

    :event     → :accent-violet  (the causal-chain accent everywhere
                                  in the Event lens)
    :app-db    → :cyan           (the App-db diff already uses cyan
                                  for highlighted state)
    :views     → :cyan           (Views is a peer of App-db; both
                                  read state, hence the shared hue —
                                  same way the spec groups them)
    :trace     → :orange         (Trace = events 'in flight'; orange
                                  is the firing/heat tone in the
                                  perf scale)
    :machines  → :green          (machine state lands in green for
                                  'final' across the inspector)
    :routing   → :yellow         (routing is the side-channel
                                  attention tone — distinguishes
                                  from app-db's main colour)
    :issues    → :red            (issues = errors; semantic red)

  JVM-portable pure data → keyword. Call sites do
  `(get tokens (panel-domain->token tab))` to materialise the hex."
  {:event    :accent-violet
   :app-db   :cyan
   :views    :cyan
   :trace    :orange
   :machines :green
   :routing  :yellow
   :issues   :red})

(defn panel-accent
  "Resolve the L4 panel's accent hex from the canonical
  `panel-domain->token` map through `tokens`. Falls back to the
  violet accent for unknown tab keywords so the stripe always
  renders."
  [tab]
  (get tokens
       (get panel-domain->token tab :accent-violet)))

(defn accent-stripe-style
  "Build an inline-style map that paints the per-panel accent as a
  3px left border on the panel's `<h1>` (or whichever element the
  caller applies it to). Inline style so per-panel call sites stay
  small + the stripe is co-located with the header chrome.

  `tab` is the L4 tab keyword (`:event` / `:app-db` / …). Returns a
  map merge-able into an existing `:style`. Per rf2-5kfxe.8."
  [tab]
  {:border-left   (str "3px solid " (panel-accent tab))
   :padding-left  "10px"})
