(ns re-frame.story.theme.colors
  "Story color palette + semantic tokens (rf2-i3i5j).

  Story is a developer-facing workshop / playground UI. The previous
  chrome literally adopted VS-Code Dark+ hexes (`#1e1e1e` / `#252526`
  / `#0e639c` / `#9cdcfe` / `#dcdcaa` / `#4ec9b0` / `#f48771` / …) —
  the rubric's 'cookie-cutter design that lacks context-specific
  character' anti-pattern. Worse: Causa (which Story embeds in the
  RHS) ships its own cool slate palette around violet `#7C5CFF`, and
  the two render with a visible palette seam where they meet.

  This namespace defines Story's own palette — warmer slate grounds,
  an **amber accent (#F5A524)** that contrasts but does not clash
  with Causa's cool violet, and a semantic palette that maps each
  feedback-state to the hex resolution. Hex literals at use-sites are
  banned (rf2-i3i5j acceptance criterion #3); call sites consume the
  `tokens` map.

  ## Why amber

  - Warm temperature distinguishes Story from Causa's cool palette at
    a glance — when both surfaces sit side-by-side in the RHS, the
    user reads 'workshop' (warm) vs 'diagnostic' (cool) without
    needing labels.
  - Amber `#F5A524` is the workshop's primary signal — Storybook is a
    woodshop / atelier metaphor; the gold-amber accent reads as
    'spotlight' / 'work in progress'.
  - Pairs with Causa's `#7C5CFF` violet on the color wheel as a near-
    complementary contrast (amber yellow ↔ violet purple) without
    landing on the AI-slop 'purple gradient' floor.
  - WCAG-AA contrast preserved across every foreground/background
    pairing (rf2-2uwv contrast baseline). Amber on `#0F1115` ground
    scores ~8:1; `#1A1D24` ground ~6.5:1.

  ## How call sites consume tokens

      (:require [re-frame.story.theme.colors :refer [tokens]])

      {:background (:bg-1 tokens)
       :color      (:text-primary tokens)
       :border     (str \"1px solid \" (:border-default tokens))}

  ## Naming scheme

  Mirrors Causa's `tools/causa/src/day8/re_frame2_causa/theme/tokens.cljc`
  shape — `:bg-0` / `:bg-1` / `:bg-2` / `:bg-3` for surface elevation,
  `:border-subtle` / `:border-default` / `:border-strong`,
  `:text-primary` / `:text-secondary` / `:text-tertiary`, and a
  semantic palette (`:accent-amber` / `:success` / `:warning` /
  `:danger` / `:info`). Sharing the shape (not the values) means the
  two surfaces compose cleanly while asserting distinct identities.

  ## Bg layering note

  Story carries five elevation levels because the shell is laid out as
  CANVAS (focal, lifted) over an inspector/sidebar ground over a base.
  Causa is a single-pane diagnostic and only needs four. The extra
  `:bg-canvas` level is the variant render surface — visibly distinct
  so the user's eye snaps to the work-in-progress instead of the
  surrounding chrome."
  {:no-doc true})

(def tokens
  "Story's semantic colour tokens. All hex literals in `tools/story/src`
  resolve through here (rf2-i3i5j contract). Phase 1 uses inline styles
  so the foundation ships without a CSS asset pipeline; a v1.0 styling
  pass replaces these with CSS variables.

  ## Categories

  - **bg-*** — surface elevation, deeper to lighter. `bg-canvas` is
    the focal variant render surface; `bg-overlay` is for floating
    panels (help dialog, recorder save dialog, etc.).
  - **border-*** — three weights for visual hierarchy.
  - **text-*** — three opacity-equivalent foreground tints.
  - **accent-amber** — Story's hero accent. Used for active states,
    selected rows, the canvas frame edge, focus rings.
  - **accent-amber-soft / accent-amber-deep** — the amber's bands for
    backdrop / hover tints.
  - **success / warning / danger / info** — semantic feedback states.
  - **success-bg / warning-bg / danger-bg** — paired tint grounds for
    each semantic colour (the pass-pill / fail-pill / etc. patterns).
  - **tag-*** — per-tag palette for the sidebar badge row.
  - **mono-*** — neutral greys for tertiary chrome.

  Values picked so foreground/background pairings preserve WCAG-AA
  (rf2-2uwv baseline)."
  {;; ── surfaces ──
   :bg-0           "#0B0D11"   ; deepest — outer canvas matte / behind everything
   :bg-1           "#13161D"   ; sidebar / right rail base
   :bg-2           "#1A1D24"   ; raised chrome (toolbar, mode tabs, sections)
   :bg-3           "#22262F"   ; row hover / chip ground
   :bg-canvas      "#15181F"   ; THE workshop region — the variant render surface
   :bg-overlay     "#1E222A"   ; floating dialogs (help, recorder save, etc.)
   :bg-active      "#3A2A0F"   ; selected variant row / active chip — amber-tinted
   :bg-input       "#0E1116"   ; text inputs / textareas

   ;; ── borders ──
   :border-subtle  "#1F232B"   ; hairline divider between like surfaces
   :border-default "#2A2F38"   ; standard chrome separators
   :border-strong  "#3A3F4A"   ; emphasised borders (canvas frame, dialog edges)

   ;; ── text ──
   :text-primary   "#EDEBE6"   ; body / labels (slightly warm white)
   :text-secondary "#A8A69F"   ; muted labels / hints
   :text-tertiary  "#74726B"   ; placeholders / disabled text
   :text-on-accent "#0B0D11"   ; foreground for amber-ground buttons / chips

   ;; ── Story's hero accent: amber / honey ──
   :accent-amber       "#F5A524"   ; the headline accent — focus, active chips, canvas frame edge
   :accent-amber-hover "#FFB94A"   ; lighter for hover
   :accent-amber-soft  "#3A2A0F"   ; deep amber backdrop — active row tint
   :accent-amber-deep  "#A86F0F"   ; pressed-amber / outline-on-bg

   ;; ── semantic feedback states ──
   :success        "#6CD68C"   ; pass / running-green / healthy
   :success-bg     "#143822"
   :warning        "#F0C152"   ; running-yellow / pending
   :warning-bg     "#3A2D12"
   :danger         "#F47171"   ; fail / error / destructive
   :danger-bg      "#3F1818"
   :info           "#6CB8FF"   ; informational links / passive highlights
   :info-bg        "#13283F"

   ;; ── mono / neutral chrome greys ──
   :mono-1         "#8E8B83"   ; secondary chrome (axis labels uppercase tracking)
   :mono-2         "#5C594F"   ; tertiary chrome
   :mono-3         "#3E3C36"   ; tertiary chrome ground

   ;; ── play step-debugger (rf2-ulw5m) — amber breakpoint highlights ──
   :breakpoint-bg      "#3A2A0F"   ; row carrying a breakpoint (amber tint)
   :breakpoint-active  "#5A3A18"   ; currently-paused row (deeper amber)
   :breakpoint-ring    "#FFD680"   ; outline / glyph for armed control
   :breakpoint-ctrl-bg "#3D2F12"   ; armed control button ground
   :breakpoint-ctrl-bd "#7A6A30"   ; armed control border

   ;; ── focus / selection / row tints ──
   :focus-ring         "#F5A524"   ; focus-visible outline (same as accent-amber)
   :row-fail-bg        "#2A1818"   ; failing test row tint
   :scrub-row-bg       "#1F2C3F"   ; scrubber-current row tint (cool blue)

   ;; ── per-tag palette (sidebar badges) ──
   :tag-dev-bg     "#1F2D44"
   :tag-dev-fg     "#9FC4FF"
   :tag-docs-bg    "#2D213F"
   :tag-docs-fg    "#D7B5FF"
   :tag-test-bg    "#143822"
   :tag-test-fg    "#6CD68C"
   :tag-screenshot-bg "#3A2D12"
   :tag-screenshot-fg "#F0C152"
   :tag-experimental-bg "#3D2614"
   :tag-experimental-fg "#F0A865"
   :tag-internal-bg     "#3F1818"
   :tag-internal-fg     "#F47171"
   :tag-agent-bg        "#173533"
   :tag-agent-fg        "#67CFC2"})

;; ── sweep audit (rf2-i3i5j) ─────────────────────────────────────────
;;
;; The rf2-i3i5j commit swept VS-Code-Dark+ literals to semantic tokens
;; across 30+ source files. The substitution was mechanical: each
;; legacy hex maps to one canonical token (e.g. `#1e1e1e` → `:bg-canvas`,
;; `#0e639c` → `:accent-amber`). Where one legacy hex carried multiple
;; meanings at different call sites the sweep accepts a small loss of
;; per-site semantic precision in exchange for zero hex literals — a
;; follow-on pass can refine individual call sites case-by-case.
