(ns re-frame.story.theme.glyphs
  "Story iconography (rf2-p0wur).

  Inline-SVG glyph set used by the sidebar (and elsewhere) so the
  three sidebar row types parse visually without reading text:

      ◆  story        — diamond glyph, the parent container
      ●  variant      — solid dot, the renderable unit (also carries
                        the per-variant status colour)
      ▦  workspace    — grid glyph, the multi-variant composition

  Every glyph is a stroke-based SVG drawn at a 16×16 viewBox with
  `currentColor` so callers control the colour via CSS. Pure-data
  hiccup — JVM-portable; no Reagent dependency.

  ## Usage

      (:require [re-frame.story.theme.glyphs :as glyphs])

      [glyphs/story-glyph 14]      ; <- size in px
      [glyphs/variant-glyph]       ; default 14px
      [glyphs/workspace-glyph 12]

  Glyph fns return hiccup. Wrap in a `<span>` if you need flex
  alignment guarantees — the SVGs are `display: inline-block;
  vertical-align: -2px` so they baseline-align with text naturally."
  {:no-doc true})

(defn- svg-wrap
  "Common SVG attributes — viewBox / currentColor / inline alignment."
  [size body]
  (into [:svg {:viewBox        "0 0 16 16"
               :width          size
               :height         size
               :fill           "none"
               :stroke         "currentColor"
               :stroke-width   "1.5"
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden    "true"
               :style          {:display        "inline-block"
                                :vertical-align "-2px"
                                :flex-shrink    "0"}}]
        body))

(defn story-glyph
  "Diamond outline — the story (parent container) glyph. Filled
  variants (e.g. an expanded story) would set `:fill` at the
  call-site."
  ([] (story-glyph 14))
  ([size]
   (svg-wrap size
     [[:path {:d "M8 1.75 L14.25 8 L8 14.25 L1.75 8 Z"}]])))

(defn variant-glyph
  "Filled dot — the variant (renderable unit) glyph. The fill
  inherits via `currentColor`, so callers paint via `:color` for
  consistency with the surrounding text."
  ([] (variant-glyph 14))
  ([size]
   (svg-wrap size
     [[:circle {:cx 8 :cy 8 :r 3 :fill "currentColor" :stroke "none"}]])))

(defn workspace-glyph
  "Grid glyph — the workspace (multi-variant composition) glyph.
  Four cells in a 2×2 grid."
  ([] (workspace-glyph 14))
  ([size]
   (svg-wrap size
     [[:rect {:x 2.5 :y 2.5 :width 4.5 :height 4.5 :rx 0.5}]
      [:rect {:x 9   :y 2.5 :width 4.5 :height 4.5 :rx 0.5}]
      [:rect {:x 2.5 :y 9   :width 4.5 :height 4.5 :rx 0.5}]
      [:rect {:x 9   :y 9   :width 4.5 :height 4.5 :rx 0.5}]])))

(defn chevron-right
  "Right-pointing chevron — used as a 'pop out' affordance on chips,
  links, and the Causa-popout escape hatch."
  ([] (chevron-right 12))
  ([size]
   (svg-wrap size
     [[:path {:d "M6 3 L11 8 L6 13"}]])))

(defn external-link
  "External-link arrow — used on 'pop out full Causa' chip + open-in-
  editor affordances."
  ([] (external-link 12))
  ([size]
   (svg-wrap size
     [[:path {:d "M6 3 H3 V13 H13 V10"}]
      [:path {:d "M9 3 H13 V7"}]
      [:path {:d "M13 3 L7 9"}]])))
