(ns re-frame.story.ui.test-mode.view-styles
  "Style map for `re-frame.story.ui.test-mode.view`. Pure data; no
  Reagent dependency. Extracted from `view.cljs` per rf2-gv5kq so the
  view ns drops below the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only — the JVM pure helpers don't need styles.")

(def styles
  {:wrap          {:flex             "1"
                   :overflow         "auto"
                   :padding          "20px 28px"
                   :background       "#1e1e1e"
                   :color            "#cccccc"
                   :font-family      "system-ui, sans-serif"
                   :font-size        "13px"
                   :line-height      "1.5"}
   :h1            {:font-family      "monospace"
                   :font-size        "18px"
                   :font-weight      "bold"
                   :color            "white"
                   :margin           "0 0 4px 0"}
   :sub           {:color            "#b0b0b0"
                   :font-family      "monospace"
                   :font-size        "11px"
                   :margin-bottom    "10px"}
   :header-row    {:display          "flex"
                   :justify-content  "space-between"
                   :align-items      "center"
                   :gap              "12px"
                   :margin           "12px 0 8px 0"}
   :rerun-btn     {:padding          "6px 14px"
                   :background       "#0e639c"
                   :color            "white"
                   :border           "none"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-family      "monospace"
                   :font-size        "11px"
                   :letter-spacing   "0.3px"}
   :rerun-running {:background       "#37373d"
                   :color            "#b0b0b0"
                   :cursor           "not-allowed"}
   :last-run      {:color            "#b0b0b0"
                   :font-family      "monospace"
                   :font-size        "10px"}
   :section       {:margin-top       "16px"}
   :section-h     {:font-weight      "bold"
                   :color            "#b0b0b0"
                   :text-transform   "uppercase"
                   :font-size        "10px"
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
                   :font-family      "monospace"
                   :font-size        "11px"
                   :font-weight      "bold"
                   :text-transform   "uppercase"
                   :letter-spacing   "0.5px"}
   :pill-pass     {:background       "#1f4d3f"
                   :color            "#4ec9b0"}
   :pill-fail     {:background       "#4d1f1f"
                   :color            "#f48771"}
   :pill-empty    {:background       "#37373d"
                   :color            "#b0b0b0"}
   :counts        {:color            "#b0b0b0"
                   :font-family      "monospace"
                   :font-size        "11px"}
   :count-pass    {:color            "#4ec9b0"}
   :count-fail    {:color            "#f48771"}
   :count-skip    {:color            "#9a9a9a"}
   :table         {:width            "100%"
                   :border-collapse  "collapse"
                   :font-family      "monospace"
                   :font-size        "11px"}
   :th            {:text-align       "left"
                   :padding          "6px 8px"
                   :background       "#2d2d30"
                   :color            "#b0b0b0"
                   :border-bottom    "1px solid #444"
                   :text-transform   "uppercase"
                   :font-size        "10px"
                   :letter-spacing   "0.5px"}
   :td            {:padding          "6px 8px"
                   :border-bottom    "1px solid #2d2d30"
                   :color            "#dcdcdc"
                   :vertical-align   "top"}
   :td-status     {:width            "20px"
                   :text-align       "center"
                   :font-size        "16px"
                   :line-height      "1"}
   :status-pass   {:color            "#4ec9b0"}
   :status-fail   {:color            "#f48771"}
   :status-skip   {:color            "#9a9a9a"}
   :row-fail      {:background       "#2a1a1a"}
   :details-tog   {:cursor           "pointer"
                   :color            "#9cdcfe"
                   :background       "none"
                   :border           "none"
                   :font-family      "monospace"
                   :font-size        "11px"
                   :padding          "0"
                   :text-decoration  "underline"}
   :detail-box    {:background       "#252526"
                   :border-left      "3px solid #f48771"
                   :padding          "8px 12px"
                   :margin-top       "6px"
                   :color            "#dcdcdc"
                   :font-family      "monospace"
                   :font-size        "11px"
                   :white-space      "pre-wrap"}
   :detail-key    {:color            "#9cdcfe"}
   :detail-source {:color            "#b0b0b0"
                   :font-size        "10px"
                   :margin-top       "4px"}
   :empty         {:padding          "32px"
                   :color            "#9a9a9a"
                   :font-style       "italic"
                   :font-family      "system-ui, sans-serif"
                   :text-align       "center"
                   :background       "#1e1e1e"
                   :flex             "1"}
   :empty-link    {:color            "#9cdcfe"
                   :font-family      "monospace"
                   :margin-top       "8px"
                   :display          "block"}

   ;; ---- step-through scrubber (rf2-lc36w) -------------------------
   :scrub-wrap    {:margin           "8px 0 0 0"
                   :padding          "8px 10px"
                   :background       "#252526"
                   :border           "1px solid #3a3a3a"
                   :border-radius    "4px"}
   :scrub-h       {:font-weight      "bold"
                   :color            "#b0b0b0"
                   :text-transform   "uppercase"
                   :font-size        "10px"
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
                   :font-family      "monospace"
                   :font-size        "10px"
                   :padding          "0 4px"
                   :user-select      "none"}
   :tick-pass     {:background       "#1f4d3f"
                   :color            "#4ec9b0"}
   :tick-fail     {:background       "#4d1f1f"
                   :color            "#f48771"}
   :tick-event    {:background       "#37373d"
                   :color            "#b0b0b0"}
   :tick-skip     {:background       "#2d2d30"
                   :color            "#9a9a9a"}
   :tick-selected {:outline          "2px solid #9cdcfe"
                   :outline-offset   "1px"}
   :scrub-slider  {:width            "100%"
                   :margin           "4px 0"}
   :scrub-detail  {:color            "#9a9a9a"
                   :font-family      "monospace"
                   :font-size        "10px"
                   :margin-top       "4px"
                   :display          "flex"
                   :gap              "10px"
                   :flex-wrap        "wrap"}
   :scrub-release {:padding          "2px 8px"
                   :background       "#5a5a5a"
                   :color            "white"
                   :border           "none"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-size        "10px"
                   :font-family      "monospace"}})
