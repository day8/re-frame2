(ns re-frame.story.ui.controls-styles
  "Style map for `re-frame.story.ui.controls`. Pure data; no Reagent
  dependency. Extracted from `controls.cljs` per rf2-gv5kq so the
  controls ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only."
  (:require [re-frame.story.theme.typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

(def styles
  {:wrap        {:padding "8px"
                 :background (:bg-2 colors/tokens)
                 :color (:text-primary colors/tokens)
                 :font-family mono-stack
                 :font-size "11px"
                 :border-top "1px solid #444"}
   :section     {:margin-bottom "12px"}
   :section-h   {:font-weight "bold"
                 :color (:text-secondary colors/tokens)
                 :text-transform "uppercase"
                 :font-size "10px"
                 :letter-spacing "0.5px"
                 :margin-bottom "4px"}
   :row         {:display "grid"
                 :grid-template-columns "120px 1fr"
                 :gap "8px"
                 :padding "2px 0"
                 :align-items "center"}
   :nested-row  {:display "grid"
                 :grid-template-columns "120px 1fr"
                 :gap "8px"
                 :padding "2px 0"
                 :padding-left "12px"
                 :border-left "1px solid #3a3a3a"
                 :margin-left "4px"
                 :align-items "center"}
   :group-h     {:color (:info colors/tokens)
                 :font-weight "bold"
                 :padding "2px 0"
                 :cursor "default"}
   :label       {:color (:info colors/tokens)}
   :sublabel    {:color (:text-secondary colors/tokens)
                 :font-size "10px"}
   :input       {:background (:bg-canvas colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border "1px solid #444"
                 :padding "2px 6px"
                 :font-family mono-stack
                 :font-size "11px"
                 :width "100%"}
   :textarea    {:background (:bg-canvas colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border "1px solid #444"
                 :padding "4px 6px"
                 :font-family mono-stack
                 :font-size "11px"
                 :width "100%"
                 :min-height "48px"
                 :resize "vertical"}
   :color-input {:background (:bg-canvas colors/tokens)
                 :border "1px solid #444"
                 :padding "0"
                 :width "36px"
                 :height "20px"
                 :cursor "pointer"}
   :radio-row   {:display "flex"
                 :flex-wrap "wrap"
                 :gap "8px"
                 :align-items "center"}
   :radio-label {:display "inline-flex"
                 :gap "4px"
                 :align-items "center"
                 :cursor "pointer"
                 :color (:text-primary colors/tokens)}
   :chip-row    {:display "flex"
                 :flex-wrap "wrap"
                 :gap "4px"}
   :chip        {:padding "2px 6px"
                 :background (:bg-3 colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border-radius "10px"
                 :cursor "pointer"
                 :font-size "10px"
                 :user-select "none"}
   :chip-active {:background (:accent-amber colors/tokens)
                 :color "white"}
   :button      {:padding "4px 8px"
                 :background (:accent-amber colors/tokens)
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"
                 :margin-top "8px"}
   :rep-button  {:padding "2px 6px"
                 :background (:bg-3 colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border "1px solid #444"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"
                 :margin-left "4px"}
   :empty       {:color (:text-tertiary colors/tokens) :font-style "italic"}})
