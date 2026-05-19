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
  pure-data side stays JVM-portable. The hex resolution happens here.

  ## Causa-MCP origin colour

  The inferential `:origin :causa-mcp` cyan (`#06B6D4`) lives in
  `mcp_server_helpers.cljc/causa-mcp-origin-colour` per the comment
  there — it is pinned to a follow-on spec bead and not (yet) a
  palette-grade token. The mcp-server panel reaches for it directly
  rather than rolling it into the shared palette."
  {:no-doc true})

(def tokens
  "Dark-theme colour tokens lifted from `spec/007-UX-IA.md` §Dark theme
  tokens. Phase 1 uses inline styles; the v1.0 styling pass replaces
  these with CSS variables."
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

(def mono-stack
  "JetBrains Mono stack per spec/007-UX-IA.md §Typography. Used by
  every panel's mono column (event vectors, EDN values, hashes,
  paths)."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def sans-stack
  "Inter stack per spec/007-UX-IA.md §Typography. Used by chrome,
  labels, prose, and every non-mono surface in the panels."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")

(def type-scale
  "Causa shell typography sizes (rf2-pcitk).

  Causa is an info-dense dev surface. Mike's UX session against the
  testbed flagged the cosy baseline (body 14 / mono 13 / line-height
  1.5) as too LARGE — the eye has to travel further than the data
  warrants. This scale runs ~1px below cosy across the board and
  tightens line-height to 1.35, which is the readability floor for
  monospaced data dumps.

  spec/007-UX-IA.md §Typography catalogues the cosy baseline and a
  ±1px density knob (compact/cosy/comfy). Rather than wire the
  runtime density toggle now (a later v1.0 styling-pass bead), we
  ship the denser scale as the default — closer to compact, but with
  a few intermediate values that suit Causa's layout. Raise every
  value 1px to revert to spec-cosy.

  Values are CSS strings so call sites can drop them straight into
  inline `:style` maps without unit conversion."
  {;; Headings + prose
   :display      "14px"     ; was 16 — panel titles
   :body         "13px"     ; was 14 — default UI text
   :body-tight   "12px"     ; sidebar entries, header chrome
   :mono-body    "12px"     ; was 13 — code, EDN
   :caption      "11px"     ; was 12 — hints, secondary labels
   :micro        "10px"     ; was 11 — badges, tabs (refused-floor in spec is 10)
   ;; Vertical rhythm
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
