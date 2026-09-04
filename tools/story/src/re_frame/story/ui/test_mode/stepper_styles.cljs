(ns re-frame.story.ui.test-mode.stepper-styles
  "Style map for the play step-debugger (spec/009 §Play step-debugger).
  Pure data; no Reagent dependency. Matches the rest of the test pane
  chrome palette."
  (:require [re-frame.story.theme.typography :as rf.story.theme.typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as rf.story.theme.colors]))

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
                    :background       (:bg-2 rf.story.theme.colors/tokens)
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:border-default rf.story.theme.colors/tokens)
                    :border-radius    "4px"
                    :outline-offset   "2px"}
   :section-header {:display          "flex"
                    :justify-content  "space-between"
                    :align-items      "center"
                    :gap              "10px"
                    :margin-bottom    "6px"
                    :font-family      mono-stack
                    :font-size        (:micro rf.story.theme.typography/type-scale)
                    :font-weight      "bold"
                    :color            (:text-secondary rf.story.theme.colors/tokens)
                    :text-transform   "uppercase"
                    :letter-spacing   "0.5px"}
   :section-title  {:display          "flex"
                    :align-items      "center"
                    :gap              "8px"}
   :progress       {:color            (:text-tertiary rf.story.theme.colors/tokens)
                    :font-weight      "normal"
                    :text-transform   "none"
                    :letter-spacing   "0"}
   :kbd-hint       {:color            (:text-tertiary rf.story.theme.colors/tokens)
                    :font-size        (:nano rf.story.theme.typography/type-scale)
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
                    :background       (:accent-amber rf.story.theme.colors/tokens)
                    :color            (:text-on-accent rf.story.theme.colors/tokens)
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:accent-amber rf.story.theme.colors/tokens)
                    :border-radius    "3px"
                    :cursor           "pointer"
                    :font-family      mono-stack
                    :font-size        (:caption rf.story.theme.typography/type-scale)
                    :letter-spacing   "0.3px"}
   :ctrl-btn-soft  {:padding-top      "4px"
                    :padding-right    "10px"
                    :padding-bottom   "4px"
                    :padding-left     "10px"
                    :background       (:bg-3 rf.story.theme.colors/tokens)
                    :color            (:text-primary rf.story.theme.colors/tokens)
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:border-strong rf.story.theme.colors/tokens)
                    :border-radius    "3px"
                    :cursor           "pointer"
                    :font-family      mono-stack
                    :font-size        (:caption rf.story.theme.typography/type-scale)
                    :letter-spacing   "0.3px"}
   :ctrl-btn-disabled
                   {:background       (:bg-2 rf.story.theme.colors/tokens)
                    :color            (:text-tertiary rf.story.theme.colors/tokens)
                    :border-color     (:border-default rf.story.theme.colors/tokens)
                    :cursor           "not-allowed"}
   :ctrl-btn-armed {:background       (:breakpoint-ctrl-bg rf.story.theme.colors/tokens)
                    :color            (:breakpoint-ring rf.story.theme.colors/tokens)
                    :border-color     (:breakpoint-ctrl-bd rf.story.theme.colors/tokens)}
   :ctrl-divider   {:width            "1px"
                    :height           "16px"
                    :background       (:border-default rf.story.theme.colors/tokens)
                    :margin           "0 2px"}

   ;; ---- step list ------------------------------------------------------
   :step-list      {:display          "flex"
                    :flex-direction   "column"
                    :gap              "2px"
                    :margin           "4px 0 2px 0"
                    :max-height       "180px"
                    :overflow-y       "auto"
                    :border-top       (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))
                    :padding-top      "6px"}
   :step-row       {:display              "flex"
                    :align-items          "center"
                    :gap                  "6px"
                    :padding-top          "3px"
                    :padding-right        "6px"
                    :padding-bottom       "3px"
                    :padding-left         "6px"
                    :font-family          mono-stack
                    :font-size            (:caption rf.story.theme.typography/type-scale)
                    :color                (:text-primary rf.story.theme.colors/tokens)
                    :cursor               "pointer"
                    :border-radius        "3px"
                    :border-left-style    "solid"
                    :border-left-width    "3px"
                    :border-left-color    "transparent"
                    :line-height          "1.4"}
   :step-row-current
                   {:background           (:scrub-row-bg rf.story.theme.colors/tokens)
                    :color                (:text-primary rf.story.theme.colors/tokens)
                    :border-left-color    (:info rf.story.theme.colors/tokens)
                    :padding-left         "3px"}
   :step-row-done  {:opacity              "0.65"}
   :step-row-pending {:color              (:text-tertiary rf.story.theme.colors/tokens)}
   :step-row-bp    {:background           (:breakpoint-bg rf.story.theme.colors/tokens)
                    :outline-style        "dashed"
                    :outline-width        "1px"
                    :outline-color        (:breakpoint-ring rf.story.theme.colors/tokens)
                    :outline-offset       "-1px"}
   :step-row-bp-current
                   {:background           (:breakpoint-active rf.story.theme.colors/tokens)
                    :outline-style        "solid"
                    :outline-width        "1px"
                    :outline-color        (:breakpoint-ring rf.story.theme.colors/tokens)
                    :outline-offset       "-1px"}
   :step-glyph     {:width            "14px"
                    :text-align       "center"
                    :font-size        (:caption rf.story.theme.typography/type-scale)
                    :line-height      "1"}
   :step-index     {:color            (:text-tertiary rf.story.theme.colors/tokens)
                    :font-size        (:micro rf.story.theme.typography/type-scale)
                    :min-width        "24px"
                    :text-align       "right"}
   :step-label     {:flex             "1"
                    :overflow         "hidden"
                    :text-overflow    "ellipsis"
                    :white-space      "nowrap"}
   :step-bp-chip   {:color            (:breakpoint-ring rf.story.theme.colors/tokens)
                    :font-size        (:micro rf.story.theme.typography/type-scale)
                    :background       "transparent"
                    :border-style     "solid"
                    :border-width     "1px"
                    :border-color     (:breakpoint-ring rf.story.theme.colors/tokens)
                    :border-radius    "2px"
                    :padding-top      "0"
                    :padding-right    "4px"
                    :padding-bottom   "0"
                    :padding-left     "4px"
                    :cursor           "pointer"
                    :font-family      mono-stack}

   ;; outcome tinting (small)
   :outcome-pass   {:color            (:success rf.story.theme.colors/tokens)}
   :outcome-fail   {:color            (:danger rf.story.theme.colors/tokens)}
   :outcome-skip   {:color            (:text-tertiary rf.story.theme.colors/tokens)}
   :outcome-event  {:color            (:text-secondary rf.story.theme.colors/tokens)}

   ;; ---- inactive placeholder ------------------------------------------
   :inactive       {:padding          "6px 0"
                    :color            (:text-tertiary rf.story.theme.colors/tokens)
                    :font-family      mono-stack
                    :font-size        (:caption rf.story.theme.typography/type-scale)
                    :font-style       "italic"}})
