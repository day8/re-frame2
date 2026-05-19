(ns re-frame.story.ui.test-mode.stepper-styles
  "Style map for the play step-debugger (rf2-ulw5m + spec/009 §Play
  step-debugger). Pure data; no Reagent dependency. Matches the rest of
  the test pane chrome (rf2-2uwv palette)."
  (:require [re-frame.story.theme.typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

(def styles
  {;; ---- section wrap ---------------------------------------------------
   :section        {:margin-top       "8px"
                    :margin-right     "0"
                    :margin-bottom    "0"
                    :margin-left      "0"
                    :padding-top      "8px"
                    :padding-right    "10px"
                    :padding-bottom   "8px"
                    :padding-left     "10px"
                    :background       (:bg-2 colors/tokens)
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:border-default colors/tokens)
                    :border-radius    "4px"
                    :outline-offset   "2px"}
   :section-header {:display          "flex"
                    :justify-content  "space-between"
                    :align-items      "center"
                    :gap              "10px"
                    :margin-bottom    "6px"
                    :font-family      mono-stack
                    :font-size        "10px"
                    :font-weight      "bold"
                    :color            (:text-secondary colors/tokens)
                    :text-transform   "uppercase"
                    :letter-spacing   "0.5px"}
   :section-title  {:display          "flex"
                    :align-items      "center"
                    :gap              "8px"}
   :progress       {:color            (:text-tertiary colors/tokens)
                    :font-weight      "normal"
                    :text-transform   "none"
                    :letter-spacing   "0"}
   :kbd-hint       {:color            (:text-tertiary colors/tokens)
                    :font-size        "9px"
                    :font-weight      "normal"
                    :text-transform   "none"
                    :letter-spacing   "0"}

   ;; ---- control button row ---------------------------------------------
   :ctrl-row       {:display          "flex"
                    :gap              "6px"
                    :align-items      "center"
                    :flex-wrap        "wrap"
                    :margin-bottom    "8px"}
   :ctrl-btn       {:padding-top      "4px"
                    :padding-right    "10px"
                    :padding-bottom   "4px"
                    :padding-left     "10px"
                    :background       (:accent-amber colors/tokens)
                    :color            "white"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:accent-amber colors/tokens)
                    :border-radius    "3px"
                    :cursor           "pointer"
                    :font-family      mono-stack
                    :font-size        "11px"
                    :letter-spacing   "0.3px"}
   :ctrl-btn-soft  {:padding-top      "4px"
                    :padding-right    "10px"
                    :padding-bottom   "4px"
                    :padding-left     "10px"
                    :background       (:bg-3 colors/tokens)
                    :color            (:text-primary colors/tokens)
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:border-strong colors/tokens)
                    :border-radius    "3px"
                    :cursor           "pointer"
                    :font-family      mono-stack
                    :font-size        "11px"
                    :letter-spacing   "0.3px"}
   :ctrl-btn-disabled
                   {:background       (:bg-2 colors/tokens)
                    :color            (:text-tertiary colors/tokens)
                    :border-color     (:border-default colors/tokens)
                    :cursor           "not-allowed"}
   :ctrl-btn-armed {:background       (:breakpoint-ctrl-bg colors/tokens)
                    :color            (:breakpoint-ring colors/tokens)
                    :border-color     (:breakpoint-ctrl-bd colors/tokens)}
   :ctrl-divider   {:width            "1px"
                    :height           "16px"
                    :background       (:border-default colors/tokens)
                    :margin           "0 2px"}

   ;; ---- step list ------------------------------------------------------
   :step-list      {:display          "flex"
                    :flex-direction   "column"
                    :gap              "2px"
                    :margin           "4px 0 2px 0"
                    :max-height       "180px"
                    :overflow-y       "auto"
                    :border-top       "1px solid #2d2d30"
                    :padding-top      "6px"}
   :step-row       {:display              "flex"
                    :align-items          "center"
                    :gap                  "6px"
                    :padding-top          "3px"
                    :padding-right        "6px"
                    :padding-bottom       "3px"
                    :padding-left         "6px"
                    :font-family          mono-stack
                    :font-size            "11px"
                    :color                (:text-primary colors/tokens)
                    :cursor               "pointer"
                    :border-radius        "3px"
                    :border-left-style    "solid"
                    :border-left-width    "3px"
                    :border-left-color    "transparent"
                    :line-height          "1.4"}
   :step-row-current
                   {:background           (:scrub-row-bg colors/tokens)
                    :color                (:text-primary colors/tokens)
                    :border-left-color    (:info colors/tokens)
                    :padding-left         "3px"}
   :step-row-done  {:opacity              "0.65"}
   :step-row-pending {:color              (:text-tertiary colors/tokens)}
   :step-row-bp    {:background           (:breakpoint-bg colors/tokens)
                    :outline-style        "dashed"
                    :outline-width        "1px"
                    :outline-color        (:breakpoint-ring colors/tokens)
                    :outline-offset       "-1px"}
   :step-row-bp-current
                   {:background           (:breakpoint-active colors/tokens)
                    :outline-style        "solid"
                    :outline-width        "1px"
                    :outline-color        (:breakpoint-ring colors/tokens)
                    :outline-offset       "-1px"}
   :step-glyph     {:width            "14px"
                    :text-align       "center"
                    :font-size        "11px"
                    :line-height      "1"}
   :step-index     {:color            (:text-tertiary colors/tokens)
                    :font-size        "10px"
                    :min-width        "24px"
                    :text-align       "right"}
   :step-label     {:flex             "1"
                    :overflow         "hidden"
                    :text-overflow    "ellipsis"
                    :white-space      "nowrap"}
   :step-bp-chip   {:color            (:breakpoint-ring colors/tokens)
                    :font-size        "10px"
                    :background       "transparent"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:breakpoint-ring colors/tokens)
                    :border-radius    "2px"
                    :padding-top      "0"
                    :padding-right    "4px"
                    :padding-bottom   "0"
                    :padding-left     "4px"
                    :cursor           "pointer"
                    :font-family      mono-stack}

   ;; outcome tinting (small)
   :outcome-pass   {:color            (:success colors/tokens)}
   :outcome-fail   {:color            (:danger colors/tokens)}
   :outcome-skip   {:color            (:text-tertiary colors/tokens)}
   :outcome-event  {:color            (:text-secondary colors/tokens)}

   ;; ---- inactive placeholder ------------------------------------------
   :inactive       {:padding          "6px 0"
                    :color            (:text-tertiary colors/tokens)
                    :font-family      mono-stack
                    :font-size        "11px"
                    :font-style       "italic"}})
