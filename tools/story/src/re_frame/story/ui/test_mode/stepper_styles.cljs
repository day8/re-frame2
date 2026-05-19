(ns re-frame.story.ui.test-mode.stepper-styles
  "Style map for the play step-debugger (rf2-ulw5m + spec/009 §Play
  step-debugger). Pure data; no Reagent dependency. Matches the rest of
  the test pane chrome (rf2-2uwv palette)."
  (:require [re-frame.story.theme.typography :refer [mono-stack]]))

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
                    :background       "#252526"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     "#3a3a3a"
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
                    :color            "#b0b0b0"
                    :text-transform   "uppercase"
                    :letter-spacing   "0.5px"}
   :section-title  {:display          "flex"
                    :align-items      "center"
                    :gap              "8px"}
   :progress       {:color            "#9a9a9a"
                    :font-weight      "normal"
                    :text-transform   "none"
                    :letter-spacing   "0"}
   :kbd-hint       {:color            "#7a7a7a"
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
                    :background       "#0e639c"
                    :color            "white"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     "#0e639c"
                    :border-radius    "3px"
                    :cursor           "pointer"
                    :font-family      mono-stack
                    :font-size        "11px"
                    :letter-spacing   "0.3px"}
   :ctrl-btn-soft  {:padding-top      "4px"
                    :padding-right    "10px"
                    :padding-bottom   "4px"
                    :padding-left     "10px"
                    :background       "#37373d"
                    :color            "#dcdcdc"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     "#4a4a4a"
                    :border-radius    "3px"
                    :cursor           "pointer"
                    :font-family      mono-stack
                    :font-size        "11px"
                    :letter-spacing   "0.3px"}
   :ctrl-btn-disabled
                   {:background       "#2d2d30"
                    :color            "#6a6a6a"
                    :border-color     "#3a3a3a"
                    :cursor           "not-allowed"}
   :ctrl-btn-armed {:background       "#5a4a1a"
                    :color            "#ffd680"
                    :border-color     "#7a6a30"}
   :ctrl-divider   {:width            "1px"
                    :height           "16px"
                    :background       "#3a3a3a"
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
                    :color                "#dcdcdc"
                    :cursor               "pointer"
                    :border-radius        "3px"
                    :border-left-style    "solid"
                    :border-left-width    "3px"
                    :border-left-color    "transparent"
                    :line-height          "1.4"}
   :step-row-current
                   {:background           "#1f3a5f"
                    :color                "#ffffff"
                    :border-left-color    "#9cdcfe"
                    :padding-left         "3px"}
   :step-row-done  {:opacity              "0.65"}
   :step-row-pending {:color              "#9a9a9a"}
   :step-row-bp    {:background           "#3a2a1a"
                    :outline-style        "dashed"
                    :outline-width        "1px"
                    :outline-color        "#ffd680"
                    :outline-offset       "-1px"}
   :step-row-bp-current
                   {:background           "#5a3a1a"
                    :outline-style        "solid"
                    :outline-width        "1px"
                    :outline-color        "#ffd680"
                    :outline-offset       "-1px"}
   :step-glyph     {:width            "14px"
                    :text-align       "center"
                    :font-size        "11px"
                    :line-height      "1"}
   :step-index     {:color            "#7a7a7a"
                    :font-size        "10px"
                    :min-width        "24px"
                    :text-align       "right"}
   :step-label     {:flex             "1"
                    :overflow         "hidden"
                    :text-overflow    "ellipsis"
                    :white-space      "nowrap"}
   :step-bp-chip   {:color            "#ffd680"
                    :font-size        "10px"
                    :background       "transparent"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     "#ffd680"
                    :border-radius    "2px"
                    :padding-top      "0"
                    :padding-right    "4px"
                    :padding-bottom   "0"
                    :padding-left     "4px"
                    :cursor           "pointer"
                    :font-family      mono-stack}

   ;; outcome tinting (small)
   :outcome-pass   {:color            "#4ec9b0"}
   :outcome-fail   {:color            "#f48771"}
   :outcome-skip   {:color            "#9a9a9a"}
   :outcome-event  {:color            "#b0b0b0"}

   ;; ---- inactive placeholder ------------------------------------------
   :inactive       {:padding          "6px 0"
                    :color            "#9a9a9a"
                    :font-family      mono-stack
                    :font-size        "11px"
                    :font-style       "italic"}})
