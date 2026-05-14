(ns re-frame.story.ui.sidebar-styles
  "Style map for `re-frame.story.ui.sidebar`. Pure data; no Reagent
  dependency. Extracted from `sidebar.cljs` per rf2-gv5kq so the
  sidebar ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only.")

(def styles
  {:wrap         {:width "260px"
                  :background "#252526"
                  :color "#cccccc"
                  :font-family "monospace"
                  :font-size "12px"
                  :border-right "1px solid #444"
                  :overflow "auto"
                  :padding "8px 0"
                  :display "flex"
                  :flex-direction "column"}
   :tree         {:flex "1"
                  :overflow "auto"
                  :display "flex"
                  :flex-direction "column"}
   :header       {:padding "8px 12px"
                  :font-weight "bold"
                  :color "#9cdcfe"
                  :border-bottom "1px solid #333"
                  :margin-bottom "4px"}
   :section      {:padding "12px 0 4px 12px"
                  :font-weight "bold"
                  :color "#b0b0b0"
                  :text-transform "uppercase"
                  :font-size "10px"
                  :letter-spacing "0.5px"}
   :tag-row      {:display "flex"
                  :flex-wrap "wrap"
                  :gap "4px"
                  :padding "8px 12px"
                  :border-bottom "1px solid #333"}
   :tag          {:padding "2px 6px"
                  :background "#37373d"
                  :color "#cccccc"
                  :border-radius "10px"
                  :cursor "pointer"
                  :font-size "10px"
                  :user-select "none"}
   :tag-active   {:background "#0e639c"
                  :color "white"}
   :story-row    {:padding "4px 12px"
                  :color "#dcdcaa"
                  :font-weight "bold"
                  :cursor "default"}
   :variant-row  {:padding "2px 12px 2px 24px"
                  :cursor "pointer"
                  :color "#cccccc"
                  :display "flex"
                  :align-items "center"
                  :gap "6px"}
   :variant-row-active {:background "#094771" :color "white"}
   :empty        {:color "#9a9a9a"
                  :font-style "italic"
                  :padding "8px 12px"}
   ;; rf2-q0irb — status dot + chrome-level test widget.
   :dot          {:width "8px"
                  :height "8px"
                  :border-radius "50%"
                  :flex-shrink "0"
                  :display "inline-block"}
   :dot-pass     {:background "#4ec9b0"}
   :dot-fail     {:background "#f48771"}
   :dot-running  {:background "#dcdcaa"
                  :opacity "0.7"}
   :dot-pending  {:background "transparent"
                  :border "1px solid #5a5a5a"}
   :widget       {:border-top "1px solid #333"
                  :margin-top "auto"
                  :padding "10px 12px"
                  :display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :background "#1f1f20"}
   :widget-h     {:font-weight "bold"
                  :color "#b0b0b0"
                  :text-transform "uppercase"
                  :font-size "10px"
                  :letter-spacing "0.5px"}
   :widget-counts {:display "flex"
                   :flex-wrap "wrap"
                   :gap "8px"
                   :font-family "monospace"
                   :font-size "11px"
                   :color "#cccccc"}
   :widget-pass  {:color "#4ec9b0"}
   :widget-fail  {:color "#f48771"}
   :widget-run   {:color "#dcdcaa"}
   :widget-pend  {:color "#9a9a9a"}
   :widget-btn   {:margin-top "4px"
                  :padding "4px 10px"
                  :background "#0e639c"
                  :color "white"
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family "monospace"
                  :font-size "11px"}
   :widget-btn-disabled {:background "#37373d"
                         :color "#9a9a9a"
                         :cursor "not-allowed"}
   :widget-empty {:color "#9a9a9a"
                  :font-style "italic"
                  :font-size "10px"}
   ;; rf2-z1h0f — watch-mode eye-icon toggle on the chrome widget.
   :watch-row    {:display     "flex"
                  :align-items "center"
                  :gap         "8px"
                  :margin-top  "2px"}
   :watch-btn    {:padding         "2px 8px"
                  :background      "transparent"
                  :color           "#9a9a9a"
                  :border          "1px solid #444"
                  :border-radius   "10px"
                  :cursor          "pointer"
                  :font-family     "monospace"
                  :font-size       "10px"
                  :letter-spacing  "0.3px"
                  :display         "inline-flex"
                  :align-items     "center"
                  :gap             "4px"}
   :watch-btn-on {:background "#1f4d3f"
                  :color      "#4ec9b0"
                  :border     "1px solid #4ec9b0"}
   ;; rf2-nwiwr — tag-as-badge affordance on variant rows.
   :tag-badges   {:display     "inline-flex"
                  :flex-wrap   "wrap"
                  :gap         "3px"
                  :margin-left "4px"}
   :tag-badge    {:padding       "0 5px"
                  :background    "#37373d"
                  :color         "#cccccc"
                  :border-radius "8px"
                  :font-family   "monospace"
                  :font-size     "9px"
                  :line-height   "14px"
                  :user-select   "none"
                  :flex-shrink   "0"}
   ;; Per-tag palette — keys on the canonical seven from
   ;; spec/007 §Inclusion tags; unknown tags fall through to
   ;; the neutral :tag-badge above.
   :tag-badge-dev          {:background "#264f78" :color "#9cdcfe"}
   :tag-badge-docs         {:background "#3a3a52" :color "#c586c0"}
   :tag-badge-test         {:background "#1f4d3f" :color "#4ec9b0"}
   :tag-badge-screenshot   {:background "#3a3a1f" :color "#dcdcaa"}
   :tag-badge-experimental {:background "#553a1f" :color "#ce9178"}
   :tag-badge-internal     {:background "#3a1f1f" :color "#f48771"}
   :tag-badge-agent        {:background "#1f3a3a" :color "#4ec9b0"}})
