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
  HCM remap, etc.) flows through unchanged. Per spec §17.4.5 the
  catalogue covers:

    - Node fill / border / radius per kind (`:standard`, `:final`,
      `:current`, `:region`).
    - Edge stroke / width / dash per kind (`:registered`,
      `:registered-traversed`, `:fired-this-epoch`).
    - Edge-label font, size, fill.

  Pure-data only — no DOM / React side effects."
  (:require [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack type-scale]]))

;; ---- node-shape conventions (§17.4.2) -----------------------------------

(defn node-style
  "Resolve the xyflow `:style` map for a node of kind `kind`. Kinds
  per spec §17.4.2:

    :standard — rounded rect, 1px `:border-default` outline.
    :final    — rounded rect + 2px `:green` outer (double-ring).
    :current  — rounded rect + 2px `:green` + 1.2s pulse animation.
    :region   — parallel-region container; dashed border.

  Unknown kinds fall back to `:standard`."
  [kind]
  (let [base {:font-family   mono-stack
              :font-size     (:body-tight type-scale)
              :color         (:text-primary tokens)
              :padding       "6px 10px"
              :border-radius "6px"
              :background    (:bg-2 tokens)}]
    (case kind
      :final    (assoc base
                       :border      (str "2px solid " (:green tokens))
                       :box-shadow  (str "inset 0 0 0 1px " (:bg-2 tokens)))
      :current  (assoc base
                       :border      (str "2px solid " (:green tokens))
                       :animation   "rf-causa-machine-pulse 1.2s ease-in-out infinite")
      :region   {:background    "transparent"
                 :border        (str "1px dashed " (:border-default tokens))
                 :border-radius "8px"}
      ;; :standard + unknown fallback
      (assoc base
             :border (str "1px solid " (:border-default tokens))))))

;; ---- edge styles (§17.4.3) ----------------------------------------------

(defn edge-style
  "Resolve the xyflow `:style` map for an edge of kind `kind`. Kinds
  per spec §17.4.3:

    :registered           — dashed `:text-tertiary` 1px (registered,
                            not fired this epoch).
    :registered-traversed — solid `:text-tertiary` 1px (most-recent
                            traversal in the buffer; not fired this
                            epoch).
    :fired-this-epoch     — solid `:accent-violet` 2px. The
                            `:animated true` xyflow prop is set
                            alongside (see `topology.cljs`).

  Unknown kinds fall back to `:registered`."
  [kind]
  (case kind
    :registered-traversed {:stroke       (:text-tertiary tokens)
                           :stroke-width 1}
    :fired-this-epoch     {:stroke       (:accent-violet tokens)
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
