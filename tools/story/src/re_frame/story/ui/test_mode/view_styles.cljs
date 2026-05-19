(ns re-frame.story.ui.test-mode.view-styles
  "Style map for `re-frame.story.ui.test-mode.view`. Pure data; no
  Reagent dependency. Extracted from `view.cljs` per rf2-gv5kq so the
  view ns drops below the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only — the JVM pure helpers don't need styles."
  (:require [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]))

(def styles
  {:wrap          {:flex             "1"
                   :overflow         "auto"
                   :padding          "20px 28px"
                   :background       (:bg-canvas colors/tokens)
                   :color            (:text-primary colors/tokens)
                   :font-family      sans-stack
                   :font-size        (:body typography/type-scale)
                   :line-height      "1.5"}
   :h1            {:font-family      mono-stack
                   :font-size        (:display typography/type-scale)
                   :font-weight      "bold"
                   :color            "white"
                   :margin           "0 0 4px 0"}
   :sub           {:color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :margin-bottom    "10px"}
   :header-row    {:display          "flex"
                   :justify-content  "space-between"
                   :align-items      "center"
                   :gap              "12px"
                   :margin           "12px 0 8px 0"}
   :rerun-btn     {:padding          "6px 14px"
                   :background       (:accent-amber colors/tokens)
                   :color            "white"
                   :border           "none"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :letter-spacing   "0.3px"}
   :rerun-running {:background       (:bg-3 colors/tokens)
                   :color            (:text-secondary colors/tokens)
                   :cursor           "not-allowed"}
   :last-run      {:color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   :section       {:margin-top       "16px"}
   :section-h     {:font-weight      "bold"
                   :color            (:text-secondary colors/tokens)
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.5px"
                   :margin-bottom    "8px"
                   :border-bottom    "1px solid #444"
                   :padding-bottom   "4px"}
   :pill-row      {:display          "flex"
                   :align-items      "center"
                   :gap              "12px"
                   :margin-bottom    "8px"}
   :pill          {:padding          "4px 10px"
                   :border-radius    "10px"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :font-weight      "bold"
                   :text-transform   "uppercase"
                   :letter-spacing   "0.5px"}
   :pill-pass     {:background       (:success-bg colors/tokens)
                   :color            (:success colors/tokens)}
   :pill-fail     {:background       (:danger-bg colors/tokens)
                   :color            (:danger colors/tokens)}
   :pill-empty    {:background       (:bg-3 colors/tokens)
                   :color            (:text-secondary colors/tokens)}
   :counts        {:color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :count-pass    {:color            (:success colors/tokens)}
   :count-fail    {:color            (:danger colors/tokens)}
   :count-skip    {:color            (:text-tertiary colors/tokens)}
   :table         {:width            "100%"
                   :border-collapse  "collapse"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :th            {:text-align       "left"
                   :padding          "6px 8px"
                   :background       (:bg-2 colors/tokens)
                   :color            (:text-secondary colors/tokens)
                   :border-bottom    "1px solid #444"
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.5px"}
   :td            {:padding          "6px 8px"
                   :border-bottom    "1px solid #2d2d30"
                   :color            (:text-primary colors/tokens)
                   :vertical-align   "top"}
   :td-status     {:width            "20px"
                   :text-align       "center"
                   :font-size        (:display typography/type-scale)
                   :line-height      "1"}
   :status-pass   {:color            (:success colors/tokens)}
   :status-fail   {:color            (:danger colors/tokens)}
   :status-skip   {:color            (:text-tertiary colors/tokens)}
   :row-fail      {:background       (:row-fail-bg colors/tokens)}
   :details-tog   {:cursor           "pointer"
                   :color            (:info colors/tokens)
                   :background       "none"
                   :border           "none"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :padding          "0"
                   :text-decoration  "underline"}
   :detail-box    {:background       (:bg-2 colors/tokens)
                   :border-left      "3px solid #f48771"
                   :padding          "8px 12px"
                   :margin-top       "6px"
                   :color            (:text-primary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :white-space      "pre-wrap"}
   :detail-key    {:color            (:info colors/tokens)}
   :detail-source {:color            (:text-secondary colors/tokens)
                   :font-size        (:micro typography/type-scale)
                   :margin-top       "4px"}
   :empty         {:padding          "32px"
                   :color            (:text-tertiary colors/tokens)
                   :font-style       "italic"
                   :font-family      sans-stack
                   :text-align       "center"
                   :background       (:bg-canvas colors/tokens)
                   :flex             "1"}
   :empty-link    {:color            (:info colors/tokens)
                   :font-family      mono-stack
                   :margin-top       "8px"
                   :display          "block"}

   ;; ---- step-through scrubber (rf2-lc36w) -------------------------
   :scrub-wrap    {:margin           "8px 0 0 0"
                   :padding          "8px 10px"
                   :background       (:bg-2 colors/tokens)
                   :border           "1px solid #3a3a3a"
                   :border-radius    "4px"}
   :scrub-h       {:font-weight      "bold"
                   :color            (:text-secondary colors/tokens)
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.5px"
                   :margin-bottom    "6px"
                   :display          "flex"
                   :justify-content  "space-between"
                   :align-items      "center"}
   :scrub-ticks   {:display          "flex"
                   :gap              "3px"
                   :align-items      "center"
                   :flex-wrap        "wrap"
                   :margin-bottom    "6px"}
   :scrub-tick    {:display          "inline-block"
                   :min-width        "14px"
                   :height           "14px"
                   :line-height      "14px"
                   :text-align       "center"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)
                   :padding          "0 4px"
                   :user-select      "none"}
   :tick-pass     {:background       (:success-bg colors/tokens)
                   :color            (:success colors/tokens)}
   :tick-fail     {:background       (:danger-bg colors/tokens)
                   :color            (:danger colors/tokens)}
   :tick-event    {:background       (:bg-3 colors/tokens)
                   :color            (:text-secondary colors/tokens)}
   :tick-skip     {:background       (:bg-2 colors/tokens)
                   :color            (:text-tertiary colors/tokens)}
   :tick-selected {:outline          "2px solid #9cdcfe"
                   :outline-offset   "1px"}
   :scrub-slider  {:width            "100%"
                   :margin           "4px 0"}
   :scrub-detail  {:color            (:text-tertiary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)
                   :margin-top       "4px"
                   :display          "flex"
                   :gap              "10px"
                   :flex-wrap        "wrap"}
   :scrub-release {:padding          "2px 8px"
                   :background       (:border-strong colors/tokens)
                   :color            "white"
                   :border           "none"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-size        (:micro typography/type-scale)
                   :font-family      mono-stack}})
