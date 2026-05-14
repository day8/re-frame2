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
   :magenta        "#E879F9"})

(def mono-stack
  "JetBrains Mono stack per spec/007-UX-IA.md §Typography. Used by
  every panel's mono column (event vectors, EDN values, hashes,
  paths)."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def sans-stack
  "Inter stack per spec/007-UX-IA.md §Typography. Used by chrome,
  labels, prose, and every non-mono surface in the panels."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")
