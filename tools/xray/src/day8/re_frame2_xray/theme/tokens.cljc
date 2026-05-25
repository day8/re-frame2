(ns day8.re-frame2-xray.theme.tokens
  "Shared design tokens for the Xray shell + every panel view.

  ## Single source of truth (rf2-rclvn)

  Phase 1 / Phase 2 / Phase 3 panel views each carried a private copy
  of the dark-theme palette plus the `mono-stack` + `sans-stack` font
  defs. Drift had already started — `:orange` was unique to the
  performance panel even though `spec/007-UX-IA.md` §Colour system
  catalogues it as part of the canonical perf scale. One source of
  truth — this ns — removes the duplication and makes the v1.0
  CSS-variable migration a one-file change.

  Per `tools/xray/spec/007-UX-IA.md` §Dark theme tokens. Phase 1 uses
  inline styles so the foundation ships without a CSS asset pipeline;
  the v1.0 styling pass replaces these with CSS variables.

  ## How panels consume this

      (:require [day8.re-frame2-xray.theme.tokens
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
    than the spec's cosy baseline, closer to compact, because Xray
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
  "Dark-theme colour tokens — the GitHub-style blue/neutral palette the
  Figma export ships (the `devtools-css` block embedded in
  `tools/xray/design-reference/xray_devtools_reference.cljs`,
  rf2-ad7zx.13). The default Xray palette; `tokens` is
  an alias of this map so the inline `(:bg-1 tokens)` call sites keep
  resolving without a runtime switch (the CSS-variable migration is the
  v1.0 styling pass).

  ## Single accent (rf2-ad7zx.13)

  The earlier orange-identity scheme (`brand`=orange, per-mode
  `accent-dynamic`/`accent-static` with a Dynamic-orange/Static-cyan
  swap) is removed — the Figma design carries a SINGLE accent (blue
  `#539bf5` dark / `#0969da` light). The Dynamic/Static MODE stays as a
  functional mode; it no longer drives accent colour."
  {;; ── surfaces ──
   ;; Neutral GitHub-dark ramp anchored on the Figma chrome-bg (#1c1c1c).
   :bg-0           "#161616"   ; deepest recess (below chrome)
   :bg-1           "#1c1c1c"   ; chrome-bg (Figma --devtools-chrome-bg)
   :bg-2           "#242424"   ; panel surface (raised)
   :bg-3           "#2a2a2a"   ; popovers / strip (= Figma --devtools-hover)
   :bg-active      "#2a2a2a"

   ;; ── chrome ribbon (rf2-xawwb · Figma-Make surface) ──
   ;; The chrome ribbon + tabs ribbon read a DEDICATED dark-chrome
   ;; band that is dark in BOTH themes — the Figma-Make surface paints
   ;; the top chrome bar and the tab strip on a near-black band with
   ;; white text, so the data canvas below pops. These map to the
   ;; Figma `--devtools-chrome-ribbon-*` + `--devtools-active-*` vars.
   :chrome-ribbon-bg              "#0d1117"   ; Figma --devtools-chrome-ribbon-bg (dark)
   :chrome-ribbon-text            "#e6edf3"   ; Figma --devtools-chrome-ribbon-text
   :chrome-ribbon-text-muted      "#8b949e"   ; Figma --devtools-chrome-ribbon-text-muted
   :chrome-ribbon-tab-active      "#2a2a2a"   ; active tab fill on the dark band
   :chrome-ribbon-tab-active-text "#e6edf3"   ; active tab ink
   :active-bg                     "#1f6feb"   ; Figma --devtools-active-bg (nav buttons)
   :active-text                   "#ffffff"   ; Figma --devtools-active-text

   ;; ── borders ──
   :border-subtle  "#2a2a2a"
   :border-default "#373737"   ; Figma --devtools-border

   ;; ── text ── (Xray carries three text levels; Figma ships two
   ;; — `--devtools-text` + `--devtools-text-muted`. Primary = Figma
   ;; text; tertiary = Figma text-muted (#8b949e); secondary is a
   ;; brighter mid (#adbac7, GitHub fg.muted) so all three clear WCAG
   ;; AA on the dark surfaces — AAA for primary/secondary.)
   :text-primary   "#e6edf3"   ; Figma --devtools-text (AAA on bg-1)
   :text-secondary "#adbac7"   ; brighter mid (AAA ≥7:1 on bg-1)
   :text-tertiary  "#8b949e"   ; Figma --devtools-text-muted (AA ≥4.5:1 on bg-1/bg-2)

   ;; ── accent (single blue — Figma --devtools-active / --devtools-changed) ──
   ;; `accent` is the SINGLE accent every chrome surface reads (active
   ;; tab · mode stripe · selected states · focus ring · changed/
   ;; recompute · L4 header stripe). Figma has no per-mode colour swap.
   :accent         "#539bf5"

   ;; ── semantic + change (Figma devtools-css block) ──
   :error          "#f85149"   ; Figma --devtools-error
   :warning        "#d29922"   ; Figma --devtools-warning
   :advisory       "#79c0ff"   ; lowest Issues severity — calm cool blue (= syntax-number)
   :success        "#3fb950"   ; Figma --devtools-success
   :dim            "#6e7681"   ; dimmed / inert / unchanged (Figma --devtools-unchanged)
   :hover          "#2a2a2a"   ; hover background (Figma --devtools-hover, = bg-3)

   ;; ── functional categorical hues (spec/022 carve-out · spec 007) ──
   ;; These do REAL semantic work — perf tiers, machine state, route
   ;; side-channel, redaction, op-family legends — and are NOT collapsed
   ;; into the accent. `:info` is the cool informational blue (Figma
   ;; syntax-number) used as a fixed categorical hue DISTINCT from the
   ;; primary `:accent` (e.g. spine-paused, sub-run, route-from).
   :green          "#3fb950"
   :yellow         "#d29922"
   :orange         "#FB923C"   ; functional amber — long-task / perf-slow tier
   :red            "#F87171"
   :magenta        "#E879F9"
   :info           "#79c0ff"   ; cool categorical blue ≠ accent (Figma syntax-number)

   ;; ── syntax-highlighter palette (rf2-79ojx · One Dark / Calva default) ──
   ;; Dedicated tokens for CLJS-value rendering in the edn-inspector widget
   ;; (`views/edn_inspector.cljs`) AND the in-bundle Clojure source-text
   ;; highlighter (`views/edn_widget/widget.cljs`). One palette, shared by
   ;; both surfaces, so a `:foo` keyword in the source-text panel paints
   ;; the same hue as `:foo` rendered as a value in the App-DB panel.
   ;;
   ;; ## Palette base (rf2-79ojx)
   ;;
   ;; Derives from the **One Dark / Atom One Dark** palette — the lineage
   ;; behind Calva's default theme + Cursive's Material/One Dark + the
   ;; VS Code "Atom One" family. CLJS programmers' eyes are trained on
   ;; this hue family (keywords magenta, strings green, numbers orange,
   ;; constants gold), so the inspector reads as syntax-highlighted at a
   ;; glance instead of demanding the eye decode a hue-distinct scheme.
   ;;
   ;; The pre-rf2-79ojx mapping inherited GitHub Primer's blue-heavy
   ;; `.syntax-*` palette — keywords salmon-red (an outlier), strings AND
   ;; numbers both in the blue family. Three of five scalar types painted
   ;; in the same hue with only luminance varying; the inspector looked
   ;; monochrome to a programmer whose eye expects keyword magenta + number
   ;; orange + string green.
   ;;
   ;; ## Hue families — the five scalar types MUST span at least four
   ;;
   ;; - keyword  → magenta (purple family) — `:foo` keyword tokens
   ;; - string   → green                   — `"hello"` strings
   ;; - number   → orange                  — `42`, `3.14`
   ;; - boolean  → gold (yellow family)    — `true`/`false` (constants)
   ;; - nil      → grey (neutral)          — deliberately muted (absence)
   ;;
   ;; Three additional tokens carry editor-convention hues for surfaces
   ;; that need them: `:syntax-symbol` (blue — identifiers / `'sym`),
   ;; `:syntax-builtin` (blue, slightly distinct — macro-call emphasis),
   ;; and `:syntax-punctuation` (muted near-text — brackets, commas).
   :syntax-keyword "#c678dd"   ; One Dark .keyword (magenta)
   :syntax-string  "#98c379"   ; One Dark .string  (green)
   :syntax-number  "#d19a66"   ; One Dark .number  (orange)
   :syntax-boolean "#e5c07b"   ; One Dark .constant (gold; "constants" family)
   :syntax-nil     "#7f848e"   ; One Dark .comment-ish dim (grey, AAA on bg-1)
   :syntax-symbol  "#61afef"   ; One Dark .function/variable (blue)
   :syntax-builtin "#61afef"   ; One Dark .function/variable (blue) — macro emphasis
   :syntax-punctuation "#abb2bf" ; One Dark .text (near-foreground; subtle)

   ;; ── deep variants (rf2-5kfxe.4) ──
   ;; Darker variant of `:red` used as a danger-button background. The
   ;; default `:red` is the standard text-on-bg accent (high lightness
   ;; for readability over the dark canvas); a button surface that
   ;; FILLS the rectangle wants a deeper hue so white text on red
   ;; stays AA-grade.
   :red-deep       "#a83a3a"

   ;; ── diff row chrome (rf2-awqts) ──
   ;; Diff signalling moved off the per-token text-colour channel so
   ;; type semantics (`:syntax-*`) stay legible inside changed rows.
   ;; Operator's eye reads diff at row-level (gutter glyph + wash +
   ;; stripe); syntax at token-level (numbers orange, booleans gold,
   ;; keywords magenta, etc.). Matches GitHub's diff convention:
   ;; changed lines get a green/red wash; code inside keeps its
   ;; syntax highlighting.
   ;;
   ;; `:diff-gutter` — single reserved hue for the `~/+/-/◴` gutter
   ;; glyphs. Cyan-teal sits OUTSIDE every `:syntax-*` family (no
   ;; magenta / green / orange / gold / blue collision) so the
   ;; operator never confuses "this is a number" with "this changed".
   ;;
   ;; `:diff-*-wash` — low-opacity row backgrounds tinted per op.
   ;; Stripe family carries the saturated 2px left-edge accent in the
   ;; same hue family — reinforces the row-level signal at the
   ;; column-1 anchor without competing with text colour.
   ;; Wash values use 8-digit hex (#RRGGBBAA) so the palette-hex-map
   ;; invariant holds — the var-resolution gate (rf2-on4cm) requires
   ;; every value match `^#[0-9A-Fa-f]+$`. Alpha bytes:
   ;;   `1a` = 26/255 ≈ 10%  (added / removed)
   ;;   `1f` = 31/255 ≈ 12%  (modified — slightly stronger to clear
   ;;                         the operator's foveal threshold on the
   ;;                         most-common diff op)
   :diff-gutter         "#5fbcb6"           ; cyan-teal (no syntax-* collision)
   :diff-added-wash     "#3fb9501a"          ; :green   @ ~10%
   :diff-modified-wash  "#d299221f"          ; :yellow  @ ~12%
   :diff-removed-wash   "#f851491a"          ; :red     @ ~10%
   :diff-added-stripe   "#3fb950"            ; :green   solid
   :diff-modified-stripe "#d29922"           ; :yellow  solid
   :diff-removed-stripe "#f85149"            ; :red     solid (= :error)

   ;; Universal white — readable on the brand/mode accent + the deep
   ;; reds. Catalogued here so the few "white text on coloured
   ;; surface" spots (primary / danger buttons) flow through tokens
   ;; like every other colour.
   :white          "#ffffff"})

(def light-palette
  "Light-theme colour tokens — the GitHub-style blue/neutral light
  palette the Figma export ships (the `devtools-css` block embedded in
  `design-reference/xray_devtools_reference.cljs`, rf2-ad7zx.13).

  Surfaces invert (bg-0 is the *lightest* deepest-canvas tone, bg-3
  the chrome strip); text inverts so primary is near-black; borders are
  light mid-greys; the single accent darkens to GitHub blue `#0969da`
  to maintain AA contrast on the white canvas.

  Consumed via the per-theme CSS custom-property block emitted by
  `theme/global-styles/themes-css`. The class toggle
  (`rf-xray-theme-light` on the shell root, written by
  `settings/effects/apply-theme!`) flips which block is in scope."
  {;; ── surfaces ── (lighter as the depth increases — bg-2 is the
   ;; brightest 'top' canvas, bg-0 the gentlest 'recess')
   :bg-0           "#fbfbfb"
   :bg-1           "#f5f5f5"   ; chrome-bg (Figma --devtools-chrome-bg)
   :bg-2           "#ffffff"   ; panel surface
   :bg-3           "#e8e8e8"   ; popovers / strip (= Figma --devtools-hover)
   :bg-active      "#e8e8e8"

   ;; ── chrome ribbon (rf2-xawwb · Figma-Make surface) ──
   ;; Dark chrome band even under the LIGHT theme — the Figma-Make
   ;; surface keeps the top chrome ribbon + tab strip dark (#2a2a2a)
   ;; with white text in light mode, so the chrome reads as a distinct
   ;; band above the light data canvas. The active tab fills WHITE on
   ;; the light surface (the tab "lifts" out of the dark band onto the
   ;; light panel below).
   :chrome-ribbon-bg              "#2a2a2a"   ; Figma --devtools-chrome-ribbon-bg (light)
   :chrome-ribbon-text            "#ffffff"   ; Figma --devtools-chrome-ribbon-text
   :chrome-ribbon-text-muted      "#b0b0b0"   ; Figma --devtools-chrome-ribbon-text-muted
   :chrome-ribbon-tab-active      "#ffffff"   ; active tab fill (lifts onto light canvas)
   :chrome-ribbon-tab-active-text "#24292f"   ; active tab ink
   :active-bg                     "#0969da"   ; Figma --devtools-active-bg (nav buttons)
   :active-text                   "#ffffff"   ; Figma --devtools-active-text

   ;; ── borders ── (light mid-greys)
   :border-subtle  "#e8e8e8"
   :border-default "#d1d1d1"   ; Figma --devtools-border

   ;; ── text ── (near-black down to mid-grey)
   :text-primary   "#24292f"   ; Figma --devtools-text
   :text-secondary "#656d76"   ; Figma --devtools-text-muted
   :text-tertiary  "#8c959f"   ; deeper muted (= Figma --devtools-unchanged)

   ;; ── accent (single blue — Figma --devtools-active / --devtools-changed) ──
   :accent         "#0969da"

   ;; ── semantic + change (Figma devtools-css block) ──
   :error          "#cf222e"   ; Figma --devtools-error
   :warning        "#9a6700"   ; Figma --devtools-warning
   :advisory       "#0550ae"   ; cool advisory blue (= syntax-number)
   :success        "#1a7f37"   ; Figma --devtools-success
   :dim            "#8c959f"   ; Figma --devtools-unchanged
   :hover          "#e8e8e8"   ; Figma --devtools-hover

   ;; ── functional categorical hues (spec/022 carve-out · spec 007) ──
   :green          "#1a7f37"
   :yellow         "#9a6700"
   :orange         "#C2570F"   ; functional amber — long-task / perf-slow tier
   :red            "#C84444"
   :magenta        "#B146C2"
   :info           "#0550ae"   ; cool categorical blue ≠ accent (Figma syntax-number)

   ;; ── syntax-highlighter palette (rf2-79ojx · One Light / Atom-One-Light) ──
   ;; Light-theme mirror of the dark-palette `:syntax-*` family, taken
   ;; from the **Atom One Light** companion to One Dark (Calva's default
   ;; light theme inherits the same family). Each hex is darkness-shifted
   ;; to clear WCAG AA on the white canvas while keeping the same hue
   ;; family as the dark variant so a user toggling themes sees the
   ;; SAME semantic colour assignment (magenta keywords / green strings /
   ;; orange numbers / gold booleans / grey nil).
   ;;
   ;; See `dark-palette` `:syntax-*` for the rationale + the hue-family
   ;; contract (the five scalar types must span at least four families).
   :syntax-keyword "#a626a4"   ; One Light .keyword (magenta)
   :syntax-string  "#50a14f"   ; One Light .string  (green)
   :syntax-number  "#986801"   ; One Light .number  (orange, AA on white)
   :syntax-boolean "#c18401"   ; One Light .constant (gold; "constants" family)
   :syntax-nil     "#a0a1a7"   ; One Light .comment-ish dim (grey)
   :syntax-symbol  "#4078f2"   ; One Light .function/variable (blue)
   :syntax-builtin "#4078f2"   ; One Light .function/variable (blue)
   :syntax-punctuation "#383a42" ; One Light .text (near-foreground; subtle)

   ;; ── deep variants ──
   ;; On the light canvas the danger button stays close to the
   ;; semantic red — depth is signalled by saturation, not lightness.
   :red-deep       "#9A3030"

   ;; ── diff row chrome (rf2-awqts) ──
   ;; Light-theme mirror of the dark-palette diff tokens. See the dark
   ;; palette's `:diff-*` block for the contract + rationale.
   ;; `:diff-gutter` darkens to clear WCAG AA on the white canvas;
   ;; wash opacities stay around 10-12% so the tinge reads as an
   ;; environmental cue, not as obscuring the text.
   ;; 8-digit hex (#RRGGBBAA) for the wash values per the dark-palette
   ;; pattern; light-theme wash colours land 10-12% over a near-white
   ;; canvas so even a 10% green reads with operator-noticeable tinge.
   :diff-gutter         "#178f86"           ; teal (darker for AA on white)
   :diff-added-wash     "#1a7f371a"          ; :green  @ ~10%
   :diff-modified-wash  "#9a67001f"          ; :yellow @ ~12%
   :diff-removed-wash   "#c844441a"          ; :red    @ ~10%
   :diff-added-stripe   "#1a7f37"            ; :green  solid
   :diff-modified-stripe "#9a6700"           ; :yellow solid
   :diff-removed-stripe "#cf222e"            ; :red    solid (= :error)

   :white          "#ffffff"})

(def themes
  "Map of theme-name → palette map. The shell toggles
  `rf-xray-theme-<name>` on its root via
  `settings/effects/apply-theme!`; `theme/global-styles/themes-css`
  emits one CSS custom-property block per theme keyed by the matching
  class selector.

  Adding a new theme is one entry here + one default in
  `settings/effects/apply-theme!`'s drop-class list (so the toggle
  stays exclusive)."
  {:dark  dark-palette
   :light light-palette})

(defn css-var
  "Map a token key (`:bg-1`, `:accent`, …) to the canonical
  `\"var(--rf-xray-<key>)\"` CSS string consumed by inline `:style`
  maps + SVG paint properties. Pure data → string; JVM-portable.

  The CSS custom-property block emitted by
  `theme/global-styles/themes-css` registers every palette key at
  `:root` (dark default) + `.rf-xray-theme-dark` / `.rf-xray-theme-
  light` so the class toggle on the shell root flips every downstream
  `var(--rf-xray-…)` reference in one assignment.

  The 357-site v1.0 sweep (rf2-on4cm) routes every inline-style read
  of a palette token through this function (via the `tokens` map,
  which now consists of `var(--…)` strings rather than hex). That
  makes the light theme actually paint — the class toggle was wired
  but inline styles were reading the dark-palette hex directly,
  so the toggle had no observable effect."
  [k]
  (str "var(--rf-xray-" (name k) ")"))

(defn with-alpha
  "Build a `color-mix(in srgb, var(--rf-xray-<key>) <pct>%, transparent)`
  CSS string. The CSS-Color-4 idiom for compositing a palette token
  with an alpha channel without forking the hex — Chrome 111+, Safari
  16.2+, Firefox 113+. Use this where the old code did string
  concatenation with a two-digit alpha suffix (`(str (:accent
  tokens) \"55\")`).

  `k`  - palette key (`:accent`, …)
  `pct` - opacity percentage (0-100). The `transparent` partner makes
          the result equivalent to that fractional alpha.

  Pure data → string; JVM-portable."
  [k pct]
  (str "color-mix(in srgb, " (css-var k) " " pct "%, transparent)"))

(def tokens
  "Xray's design palette, exposed as CSS-variable strings.

  Every value is `\"var(--rf-xray-<key>)\"` — the CSS custom-property
  registered by `theme/global-styles/themes-css` against the active
  theme class (`rf-xray-theme-dark` / `rf-xray-theme-light`). Inline
  `:style` reads of `(:bg-1 tokens)` resolve to `\"var(--rf-xray-bg-1)\"`
  and the browser substitutes the dark or light hex at paint time
  based on the class toggle on the shell root.

  ## Why a var-map rather than a hex-map (rf2-on4cm)

  Pre-rf2-on4cm `tokens` was an alias of `dark-palette` — every inline
  style site (~357 of them) read a hardcoded dark-palette hex
  regardless of the active theme class. The light-theme class toggle
  was wired through `settings/effects/apply-theme!` and the CSS
  variable block was emitted, but inline styles ignored both — light
  mode was paint-only-the-edges broken.

  Replacing the hex map with a CSS-variable map flips every existing
  call site to the variable surface without per-site edits: a token
  read like `(:bg-1 tokens)` now yields the `var(--rf-xray-bg-1)`
  reference, and the active theme's class scope on the shell root
  decides which palette resolves at paint time.

  ## The few sites that still need raw hex

  Two call sites consume the literal hex rather than a CSS variable:

  - `mount.cljs`'s popout opener-gone overlay paints into the popout
    window's `<body>` imperatively (`set! style.background`) — the
    popout document doesn't carry the Xray `<style>` injection, so
    `var(--rf-xray-bg-0)` would resolve to the default initial value.
    These reads use `dark-palette` directly.
  - `config.cljc`'s `default-accent` publishes a literal hex INTO the
    `--rf-xray-accent` CSS variable as its default value, so it
    must remain a hex string.

  Other consumers (SVG `:fill`, inline `:style :background`, etc.)
  flow through `var(...)` unchanged — modern browsers (Chrome 49+,
  Firefox 31+, Safari 9.1+) accept CSS custom properties in every
  paint property including SVG attributes."
  (into {}
        (for [k (keys dark-palette)]
          [k (css-var k)])))

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
  "Fraunces stack — Xray's display face (rf2-5kfxe.9).

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
  multiplication at paint time, so changing `:root { --rf-xray-font-
  size: 16px }` rescales every typographic surface ~1.23x without a
  code change."
  "--rf-xray-font-size")

(def font-size-default
  "Default value of `--rf-xray-font-size` published on `:root` by
  `theme/global-styles/motion-css`. This is the historical Xray
  baseline (`:body` = 13px); the per-key multipliers below are
  expressed RELATIVE to it (e.g. `:caption` = 0.85 → ~11px at the
  default knob).

  Catalogued here rather than only in the CSS string so the JVM
  side has the literal default for tests / inspection without
  parsing a calc() expression."
  "13px")

(def type-scale-multipliers
  "Pure-data multipliers used to derive each `type-scale` entry from
  the `--rf-xray-font-size` knob. Catalogued separately from the
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
  "Build the canonical `calc(var(--rf-xray-font-size, 13px) * N)`
  CSS string each `type-scale` entry uses. One knob — change `:root
  { --rf-xray-font-size: 16px }` and every derived size rescales
  in lockstep.

  Pure data → string; JVM-portable so the .cljc test surface
  exercises the calc shape on the JVM runner."
  [multiplier]
  (str "calc(var(" font-size-var-name ", " font-size-default ") * "
       multiplier ")"))

(def type-scale
  "Xray shell typography sizes (rf2-pcitk + rf2-n8i2c font-size-var
  migration).

  Xray is an info-dense dev surface. Mike's UX session against the
  testbed flagged the cosy baseline (body 14 / mono 13 / line-height
  1.5) as too LARGE — the eye has to travel further than the data
  warrants. This scale runs ~1px below cosy across the board and
  tightens line-height to 1.35, which is the readability floor for
  monospaced data dumps.

  ## One knob, whole scale (rf2-n8i2c)

  Every size below resolves through the `--rf-xray-font-size` CSS
  custom property (default `13px`, published on `:root` by
  `theme/global-styles/motion-css`). Each entry is
  `calc(var(--rf-xray-font-size, 13px) * <multiplier>)` where the
  multiplier expresses the entry's RELATIVE size — `:body` is the
  1.0 anchor, `:display` is 1.077, `:caption` is 0.846, and so on.

  Modelled on TanStack Query Devtools' `--tsqd-font-size` knob: one
  variable scales the entire UI. Override `--rf-xray-font-size` at
  `:root` (or under a `.rf-xray-density-compact` / `-comfy` class
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
  "Xray shell layout dimensions (rf2-pcitk + rf2-g9pee). Single source
  for the chrome's fixed-height layer measurements.

  The 4-layer chrome is L1 ribbon (top-strip) + L2 event list + L3
  tab bar + L4 detail panel — no bottom rail, no sidebar (both dropped
  in earlier Xray redesigns and the now-unused `:sidebar-width` /
  `:bottom-rail-height` tokens were retired in Round-3 rf2-g9pee).

  Per rf2-4vp5j the top of the shell splits into TWO strata, reconciled
  to the authoritative reference (`tools/xray/design-reference/xray_
  devtools_reference.cljs`, rf2-3f2di) where every bar is a uniform
  34px (`chrome-ribbon` / `events-ribbon` `:height \"34px\"`):

  - the **chrome ribbon** (`:top-strip-height`, 34px) — bar-1, carrying
    the `Events` label + the `[◀ ▶ ⏭]` nav cluster + `Filters:` + the
    add(+) on the left, and the Frame dropdown + Dynamic/Static dropdown
    + settings + close on the right (the reference chrome-ribbon
    layout).
  - the **events ribbon** (`:events-ribbon-height`, 34px) — bar-2,
    carrying the `N events filtered out` warning text + the green/red
    filter pills (the reference events-ribbon layout).

  The reference's uniform 34px rhythm (rf2-3f2di) supersedes the prior
  32px/36px split (rf2-cplj8)."
  {:top-strip-height     "34px"
   :events-ribbon-height "34px"})

;; ---- motion (rf2-5kfxe.5) ----------------------------------------------

(def motion
  "Motion-axis tokens (rf2-5kfxe.5). Every Xray animation interpolates
  its duration through `--rf-xray-motion-scale`, a CSS custom
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
  {:scale-var-name    "--rf-xray-motion-scale"
   :flash-duration-ms 400
   :fade-duration-ms  180})

(defn duration-css
  "Build the canonical `calc(<ms>ms * var(--rf-xray-motion-scale, 1))`
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
  "Pure semantic map from L4 tab keyword → token keyword for the
  panel's header stripe colour.

  ## Single accent (rf2-ad7zx.13 / spec/022 · spec/007 §L4 panel accent
  stripe)

  The prior per-panel DOMAIN-colour mapping (`:event` violet ·
  `:app-db`/`:views` cyan · `:trace` orange · `:machines` green ·
  `:routing` yellow · `:issues` red) is **superseded**. The Figma
  export carries a SINGLE accent identity (GitHub blue) — every panel's
  3px header stripe reads `:accent`, so the stripe is a consistent
  signal, not a per-panel domain colour. Surfaces stay neutral so the
  blue accent pops.

  Every tab therefore maps to the `:accent` token. Domain colour still
  does load-bearing work INSIDE each panel where it is semantic
  (`error` red in Issues, machine `green`, route `yellow`, the
  op-family bands in Trace, the per-panel header icons §021 §17.1.5) —
  but the HEADER STRIPE is the single accent.

  The map is retained (rather than collapsed to a constant) so the
  per-tab inventory stays explicit and a future per-panel signal can
  re-diverge a single entry without restructuring call sites.

  JVM-portable pure data → keyword. Call sites do
  `(get tokens (panel-domain->token tab))` to materialise the
  CSS-variable string."
  {:event           :accent
   :app-db          :accent
   :views           :accent
   :trace           :accent
   :machines        :accent
   :routing         :accent
   :issues          :accent})

(defn panel-accent
  "Resolve the L4 panel's header-stripe accent CSS-variable string —
  the single `:accent` (GitHub blue) per spec/007 §L4 panel accent
  stripe + spec/022. Falls back to the `:accent` variable for unknown
  tab keywords so the stripe always renders.

  Returns a `\"var(--rf-xray-<key>)\"` string post rf2-on4cm — the
  active theme class scope on the shell root decides which palette's
  hex (`#539bf5` dark / `#0969da` light) resolves at paint time."
  [tab]
  (get tokens
       (get panel-domain->token tab :accent)))

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

;; ---- spacing scale (rf2-ezx8w · spec/021 §17.1.1) -----------------------

(def spacing
  "Xray's 4-px-base spacing scale per spec/021 §17.1.1. Density is
  binding (§0); spacing reinforces it. Every gap / pad value across
  the panels is a multiple of 4 — this map catalogues the canonical
  steps so per-panel implementations stop guessing.

  | Key      | Pixels | Use                                                |
  |----------|--------|----------------------------------------------------|
  | `:gap-0` | 0      | Adjacent inline glyphs (e.g. diff-glyph + value)   |
  | `:gap-1` | 4px    | Tight inline gap (icon → label inside a chip)      |
  | `:gap-2` | 8px    | Between sibling rows in dense tables               |
  | `:gap-3` | 12px   | Between major sections inside a panel              |
  | `:gap-4` | 16px   | Panel inner padding (top/right/bottom/left)        |
  | `:gap-5` | 20px   | Between distinct cards / canvases                  |
  | `:gap-6` | 24px   | Between zones inside a panel                       |

  Values are CSS strings so call sites drop them straight into inline
  `:style` maps (e.g. `:padding (:gap-4 spacing)` for the canonical
  16px panel pad). Pure data; JVM-portable.

  Padding inside cards (the canvas frame around each per-machine
  xyflow render) is `:gap-3` (12px) — workstation density, not
  consumer breathing room."
  {:gap-0 "0"
   :gap-1 "4px"
   :gap-2 "8px"
   :gap-3 "12px"
   :gap-4 "16px"
   :gap-5 "20px"
   :gap-6 "24px"})

;; ---- per-panel header icons (rf2-ezx8w · spec/021 §17.1.5) --------------

(def panel-icon
  "Per-panel header glyph map per spec/021 §17.1.5 iconography. Each
  L4 tab carries a Unicode glyph rendered to the LEFT of the panel
  `<h1>` (or its inline-stripe header equivalent). Colour resolves
  through `panel-domain->token` so the glyph rides the panel's domain
  hue — a visual tie between the accent stripe and the icon.

  | Tab           | Glyph |
  |---------------|-------|
  | `:event`      | ⚡    |
  | `:reactive`   | ◉    |
  | `:views`      | ◉    |
  | `:app-db`     | ◐    |
  | `:trace`      | ⬢    |
  | `:machines`   | ◆    |
  | `:routing`    | 🌐    |
  | `:issues`     | ⚠    |

  (rf2-4v67l — the Chrome A11y glyph was removed alongside the panel
  itself; a11y dogfooding is now Story's concern per rf2-18t6p +
  rf2-qgms1.)

  Emoji glyphs are deliberate — consistent with existing Xray
  convention (the L2-row badges + the Routing 🌐 affordance already
  ship emoji). Under HCM the @media (forced-colors: active) block
  strips the color; the glyph alone carries the signal — colour is
  never alone (§007)."
  {:event           "⚡"
   :reactive        "◉"
   :views           "◉"
   :app-db          "◐"
   :trace           "⬢"
   :machines        "◆"
   :routing         "🌐"
   :issues          "⚠"})

(defn panel-icon-style
  "Build an inline-style map for the panel header icon span. Resolves
  the panel's domain colour through `panel-accent` so the glyph rides
  the same hue as the 3px accent stripe. Caller adds an 8px right
  margin (or `:gap` from a flex parent) so the icon sits to the LEFT
  of the panel's title per §17.1.5.

  Returns a map merge-able into an existing `:style`."
  [tab]
  {:color       (panel-accent tab)
   :font-weight 600
   :font-size   "14px"
   :line-height 1})
