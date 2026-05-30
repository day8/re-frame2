(ns re-frame.story.ui.shell-styles
  "Style map for `re-frame.story.ui.shell`. Pure data; no Reagent
  dependency. Extracted from `shell.cljs` per rf2-gv5kq so the shell
  ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  ## Visual-foundation pass (rf2-ba86n.3, spec/018 §12)

  This is the flagship shell composition the whole Story UI inherits.
  The decisions here set the language every region picks up:

  - **Canvas-first.** The shell reads as CANVAS (focal) flanked by
    supporting rails. The main pane carries no chrome ground of its own
    so the variant render surface is the visual protagonist (§12.1); the
    sidebar + inspector sit one elevation back on `:bg-1` so the eye
    lands on the work, not the frame.
  - **Structural bands, not nested cards.** Page regions are panes /
    bands (§12.2 — 'Page regions should be structural bands or panes,
    not nested decorative cards'). Cards are reserved for repeated items
    and dialogs, which live in their own region files.
  - **The Story↔Xray seam (§12.9).** The RHS stacks Story-owned
    sections (Explain / Evidence / Controls / Dispatch — WARM amber
    section labels) above an embedded Xray panel (COOL-violet section
    label). One border, one title row per section; the temperature
    split tells the user 'Story brought me here; Xray is showing the
    detail' without a stitched-together feel.
  - **Shared tokens.** Spacing resolves through `theme.space` (the 4px
    rhythm), colour through `theme.colors` (zero raw hex — rf2-i3i5j),
    depth through `theme.depth`. No literal px / hex strings live here.

  CLJS-only."
  (:require [re-frame.story.theme.typography :as typography :refer [sans-stack]]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.depth :as depth]
            [re-frame.story.theme.space :as space]))

(def styles
  {:root      {:display "flex"
               :flex-direction "column"
               :height "100vh"
               :font-family sans-stack
               ;; rf2-ypd6h: atmospheric backdrop — radial-gradient mesh
               ;; over the deepest slate ground, lifts the shell out of
               ;; the 'editor pane' flat-solid floor.
               :background (:shell-root depth/backdrops)
               :color (:text-primary colors/tokens)}
   :body      {:display "flex"
               :flex-direction "row"
               :flex "1"
               :min-height "0"
               :overflow "hidden"}
   :body-narrow {:flex-direction "column"
                 :overflow "auto"}
   ;; CANVAS-FIRST (§12.1) — the main pane carries NO ground of its own.
   ;; It sits transparent over the shell's atmospheric backdrop so the
   ;; framed variant (which DOES carry `:bg-canvas` + the amber edge) is
   ;; the only lifted surface in the centre column. The eye lands on the
   ;; work, not on the chrome around it.
   :main      {:display "flex"
               :flex-direction "column"
               :flex "1"
               :min-width "0"
               :overflow "hidden"}
   ;; The inspector rail sits ONE elevation back (:bg-1) from the canvas
   ;; so it reads as supporting chrome, never as a competing surface
   ;; (§12.4 — selected rendered state is the first visual priority).
   :right     {:flex-shrink "0"
               :display "flex"
               :flex-direction "column"
               :border-left (str "1px solid " (:border-default colors/tokens))
               :background (:bg-1 colors/tokens)
               :box-shadow (:elev-1 depth/shadows)
               :overflow "auto"}
   :right-narrow {:width "auto"
                  :max-height "42vh"
                  :border-left "none"
                  :border-top (str "1px solid " (:border-default colors/tokens))}
   ;; The splitter is a quiet seam, not a chrome element — it reads as
   ;; the edge between panes, lighting up amber only while dragged.
   :splitter  {:width "10px"
               :flex "0 0 10px"
               :background "transparent"
               :border "0"
               :border-left (str "1px solid " (:border-subtle colors/tokens))
               :border-right (str "1px solid " (:border-subtle colors/tokens))
               :cursor "col-resize"
               :padding "0"
               :position "relative"}
   :splitter-grip {:position "absolute"
                   :top "50%"
                   :left "3px"
                   :width "2px"
                   :height "36px"
                   :transform "translateY(-50%)"
                   :border-radius (:pill space/radius)
                   :background (:border-strong colors/tokens)}
   :splitter-active {:background (:accent-amber-soft colors/tokens)}
   ;; rf2-pxeko — `?` help-button chip lives top-LEFT of the viewport.
   ;; The top-RIGHT corner is reserved for the Test-Codegen REC chip
   ;; (`recorder/rec-chip` in the toolbar) plus the recording-overlay
   ;; banner (`recorder/recording-overlay` at top:44px right:12px); a
   ;; floating `?` on the right occluded the REC affordance. Top-left
   ;; sits over the toolbar's first axis-label only — no fixed-position
   ;; conflict with the sidebar (which is part of the flex layout, not
   ;; fixed-positioned) or any other chrome affordance.
   :help-slot {:position "fixed"
               :top      (space/pad 4)
               :left     (space/pad 5)
               :z-index  1500}
   ;; ── RHS section bands (§12.3 Inspector + §12.9 the Xray seam) ──
   ;;
   ;; Each inspector tenant (Xray / Explain / Evidence / Controls /
   ;; Dispatch / story-panels) renders as a labelled structural band.
   ;; Pre-rf2-8rvu4 the rail stacked widgets with only a thin border
   ;; between them, reading as one tall column; the section-header
   ;; pattern gives each widget a labelled band so the panel parses as
   ;; N labelled sections. The visual-foundation pass keeps that shape
   ;; and adds the Story↔Xray temperature seam.
   :rhs-section
   {:padding        (str (space/pad 5) " " (space/pad 5) " 0 " (space/pad 5))
    :background     (:bg-1 colors/tokens)}
   :rhs-section-h
   {:display        "flex"
    :align-items    "center"
    :gap            (space/gap 3)
    :font-family    sans-stack
    :font-size      (:nano typography/type-scale)
    :font-weight    "600"
    :letter-spacing "0.08em"
    :text-transform "uppercase"
    :color          (:text-tertiary colors/tokens)
    :margin-bottom  (space/pad 4)
    :padding-bottom (space/pad 3)
    ;; Story-owned sections wear a WARM amber-tinted hairline — the
    ;; same trick Xray uses for its own spine boundaries, but in
    ;; Story's identity colour so the band reads 'workshop'.
    :border-bottom  (str "1px solid " (:border-subtle colors/tokens))
    :box-shadow     (str "0 1px 0 " (:accent-amber-soft colors/tokens))}
   ;; A muted sublabel that sits after the section title (e.g. 'args +
   ;; modes', 'provenance + lowering') — the second word in the header
   ;; row that tells the user what the band is for without a tooltip.
   :rhs-section-sub
   {:font-weight    "400"
    :color          (:text-tertiary colors/tokens)
    :letter-spacing "0.04em"}
   ;; ── the Xray seam header (§12.9) ──
   ;; The Xray band's header is the ONE place the RHS goes cool. The
   ;; violet accent + violet-tinted underline echo Xray's own identity
   ;; so the diagnostic boundary reads from temperature. Story still
   ;; owns this chrome (the title row, the chip row below it) — only the
   ;; ACCENT points at the embedded surface (§12.9 — 'Story owns outer
   ;; inspector chrome … Xray owns diagnostic panel interiors').
   :rhs-section-h-xray
   {:color          (:seam-xray colors/tokens)
    :box-shadow     (str "0 1px 0 " (:seam-xray-soft colors/tokens))}
   :rhs-section-sub-xray
   {:color          (:seam-xray colors/tokens)
    :opacity        "0.7"}
   :rhs-section-body
   {:padding-bottom (space/pad 5)}
   ;; ── calm empty state (§12.5) — 'no variant or workspace selected' ──
   ;; Not an explanatory poster: a quiet centred prompt that names what
   ;; is missing and the command available now (pick from the sidebar).
   ;; Lives on the transparent main pane so it reads as 'the canvas is
   ;; waiting', not as a chrome panel.
   :empty-canvas
   {:flex            "1"
    :display         "flex"
    :flex-direction  "column"
    :align-items     "center"
    :justify-content "center"
    :gap             (space/gap 3)
    :padding         (space/pad 8)
    :text-align      "center"
    :color           (:text-tertiary colors/tokens)}
   :empty-canvas-glyph
   {:font-size       (:hero typography/type-scale)
    :color           (:accent-amber colors/tokens)
    :opacity         "0.5"}
   :empty-canvas-title
   {:font-family     sans-stack
    :font-size       (:display typography/type-scale)
    :font-weight     (str (:semibold typography/weights))
    :color           (:text-secondary colors/tokens)
    :letter-spacing  (:display typography/letter-spacing)}
   :empty-canvas-hint
   {:font-family     sans-stack
    :font-size       (:caption typography/type-scale)
    :color           (:text-tertiary colors/tokens)
    :max-width       "32ch"
    :line-height     (:line-height-body typography/type-scale)}})
