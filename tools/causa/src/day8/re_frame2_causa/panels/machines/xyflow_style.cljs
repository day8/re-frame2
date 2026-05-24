(ns day8.re-frame2-causa.panels.machines.xyflow-style
  "Causa-palette style props for xyflow nodes + edges (rf2-uwvyj ·
  spec/021 §17.4.5 — the single source of truth for xyflow visual
  props).

  ## Why a separate ns

  The wrapper (`xyflow_wrapper.cljs`) owns the React-class adapt + the
  Reagent boundary; this ns owns the visual contract — node-shape
  conventions (§17.4.2), edge-styles (§17.4.3), token mapping
  (§17.4.5). The topology projector (`topology.cljs`) reads these
  maps, never inlines hex literals.

  ## Token integration

  Reads from `theme/tokens` so a future palette swap (dark → light,
  HCM remap, etc.) flows through unchanged. Per spec §17.4.5 +
  §6.2 Case C (Figma reconcile — rf2-ad7zx.10) the catalogue covers:

    - Node fill / border / radius per kind (`:standard`, `:final`,
      `:current`, `:from`, `:compound`, `:region`).
    - Edge stroke / width / dash per kind (`:registered`,
      `:registered-traversed`, `:fired-this-epoch`).
    - Edge-label font, size, fill.

  ## Figma reconcile (rf2-ad7zx.10 · §6.2 Case C ·
  design-reference/causa_devtools_reference.cljs, the `machine-panel` component)

  The later Figma iteration draws the focused FROM/TO transition as
  circle nodes inside a dashed `active (compound)` container:

    - `:from`     — the source state of the fired transition. Dashed,
                    dimmed circle (`:text-tertiary`); reads as the state
                    we came FROM without competing with the active TO.
    - `:current`  — the TO / live state. A **double-circle** (concentric
                    ring) painted in the mode `:accent` (orange Dynamic /
                    cyan Static), with the breathing pulse. This replaces
                    the former single-border + green pulse so the TO node
                    reads as the Stately/xstate active-node convention the
                    Figma fixes.
    - `:compound` — the dashed `active (compound)` grouping container that
                    bounds the focused FROM/TO pair. Distinct from
                    `:region` (parallel-region siblings); the compound
                    container is the single focused-transition lens.

  Pure-data only — no DOM / React side effects."
  (:require [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack type-scale]]))

;; ---- node-shape conventions (§17.4.2 + §6.2 Case C) --------------------

(def ^:private state-base
  "Shared base for the textual state nodes (`:standard` / `:final`)."
  {:font-family   mono-stack
   :font-size     (:body-tight type-scale)
   :color         (:text-primary tokens)
   :padding       "6px 10px"
   :border-radius "6px"
   :background    (:bg-2 tokens)})

(def ^:private circle-base
  "Shared base for the Figma circle nodes (`:from` / `:current`).
  Square aspect + `border-radius: 50%` reproduces the SVG `<circle>`
  the design-reference draws for the focused FROM/TO pair, rendered
  through an xyflow HTML node rather than hand-rolled SVG (B.4.1 lock —
  topology stays xyflow)."
  {:font-family     mono-stack
   :font-size       (:body-tight type-scale)
   :display         "flex"
   :align-items     "center"
   :justify-content "center"
   :width           "64px"
   :height          "64px"
   :border-radius   "50%"
   :text-align      "center"})

(defn node-style
  "Resolve the xyflow `:style` map for a node of kind `kind`. Kinds
  per spec §17.4.2 + §6.2 Case C (Figma reconcile · rf2-ad7zx.10):

    :standard — rounded rect, 1px `:border-default` outline.
    :final    — rounded rect + 2px `:green` outer (double-ring).
    :current  — DOUBLE-CIRCLE concentric active node in the mode
                `:accent`, with the breathing pulse (Figma TO/current).
    :from     — dashed/dim circle (`:text-tertiary`); the source state
                of the focused fired transition (Figma FROM).
    :compound — dashed `active (compound)` grouping container that bounds
                the focused FROM/TO pair.
    :region   — parallel-region container; dashed border.

  Unknown kinds fall back to `:standard`."
  [kind]
  (case kind
    :final    (assoc state-base
                     :border      (str "2px solid " (:green tokens))
                     :box-shadow  (str "inset 0 0 0 1px " (:bg-2 tokens)))
    :current  (assoc circle-base
                     :color       (:accent tokens)
                     :font-weight 600
                     :background  (t/with-alpha :accent 10)
                     :border      (str "3px solid " (:accent tokens))
                     ;; Double-circle (concentric ring): the inner gap +
                     ;; inner ring are painted as stacked box-shadows so
                     ;; the TO node reads as the Stately active double-
                     ;; circle. The breathing pulse layers a fading accent
                     ;; halo on top via the accent pulse keyframe.
                     :box-shadow  (str "inset 0 0 0 3px " (:bg-1 tokens) ", "
                                       "inset 0 0 0 5px " (:accent tokens))
                     :animation   "rf-causa-machine-pulse-active 1.2s ease-in-out infinite")
    :from     (assoc circle-base
                     :color            (:text-tertiary tokens)
                     :background       "transparent"
                     :border           (str "2px dashed " (:text-tertiary tokens)))
    :compound {:background    "transparent"
               :border        (str "2px dashed " (:border-default tokens))
               :border-radius "8px"
               :color         (:text-tertiary tokens)
               :font-family   mono-stack
               :font-size     (:micro type-scale)}
    :region   {:background    "transparent"
               :border        (str "1px dashed " (:border-default tokens))
               :border-radius "8px"}
    ;; :standard + unknown fallback
    (assoc state-base
           :border (str "1px solid " (:border-default tokens)))))

;; ---- edge styles (§17.4.3) ----------------------------------------------

(defn edge-style
  "Resolve the xyflow `:style` map for an edge of kind `kind`. Kinds
  per spec §17.4.3:

    :registered           — dashed `:text-tertiary` 1px (registered,
                            not fired this epoch).
    :registered-traversed — solid `:text-tertiary` 1px (most-recent
                            traversal in the buffer; not fired this
                            epoch).
    :fired-this-epoch     — solid mode `:accent` 2px (orange Dynamic /
                            cyan Static, rf2-ad7zx). The `:animated
                            true` xyflow prop is set alongside (see
                            `topology.cljs`).

  Unknown kinds fall back to `:registered`."
  [kind]
  (case kind
    :registered-traversed {:stroke       (:text-tertiary tokens)
                           :stroke-width 1}
    :fired-this-epoch     {:stroke       (:accent tokens)
                           :stroke-width 2}
    ;; :registered + unknown fallback
    {:stroke           (:text-tertiary tokens)
     :stroke-width     1
     :stroke-dasharray "4 4"}))

(defn animated?
  "Whether the edge of kind `kind` should set xyflow's `:animated`
  prop (the moving dashed-line affordance). Only `:fired-this-epoch`
  animates per spec §17.4.3."
  [kind]
  (= kind :fired-this-epoch))

;; ---- edge label (§17.4.3) -----------------------------------------------

(def edge-label-style
  "Style map for the xyflow `:label-style` prop — JetBrains Mono, the
  `:micro` size, `:text-secondary` fill. Inline on the edge, not in a
  side legend (spec §17.4.3)."
  {:fill        (:text-secondary tokens)
   :font-family mono-stack
   :font-size   (:micro type-scale)})

(def edge-label-bg-style
  "Style for the small label rect xyflow renders behind edge labels
  so they don't smear over the edge stroke. Matches the canvas
  background so the label appears 'cut out' of the edge."
  {:fill         (:bg-2 tokens)
   :fill-opacity 0.9})
