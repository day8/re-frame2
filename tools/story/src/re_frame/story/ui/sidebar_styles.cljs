(ns re-frame.story.ui.sidebar-styles
  "Style map for `re-frame.story.ui.sidebar`. Pure data; no Reagent
  dependency. Extracted from `sidebar.cljs` per rf2-gv5kq so the
  sidebar ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only."
  (:require [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.motion :as motion]))

(def styles
  {:wrap         {:width "260px"
                  :background (:bg-1 colors/tokens)
                  :color (:text-primary colors/tokens)
                  :font-family sans-stack
                  :font-size (:body-tight typography/type-scale)
                  :border-right (str "1px solid " (:border-default colors/tokens))
                  :overflow "auto"
                  :padding "8px 0 0 0"
                  :display "flex"
                  :flex-direction "column"}
   :tree         {:flex "1"
                  :overflow "auto"
                  :display "flex"
                  :flex-direction "column"
                  :padding-bottom "8px"}
   ;; rf2-p0wur — the sidebar header reads as Story's "Stories" lens
   ;; label, parity with the RHS section headers (rf2-8rvu4 §rhs-section-h
   ;; in shell-styles). Uppercase + wide tracking + nano scale + a
   ;; subtle amber underline so the sidebar matches the workshop
   ;; chrome's section-label vocabulary.
   :header       {:padding "10px 12px 8px 12px"
                  :font-family sans-stack
                  :font-weight (str (:semibold typography/weights))
                  :font-size (:nano typography/type-scale)
                  :text-transform "uppercase"
                  :letter-spacing (:label-wide typography/letter-spacing)
                  :color (:accent-amber colors/tokens)
                  :border-bottom (str "1px solid " (:border-subtle colors/tokens))
                  :box-shadow (str "0 1px 0 " (:accent-amber-soft colors/tokens))
                  :margin-bottom "6px"
                  :display "flex"
                  :align-items "center"
                  :gap "8px"}
   :section      {:padding "16px 0 6px 12px"
                  :margin-top "4px"
                  :border-top (str "1px solid " (:border-subtle colors/tokens))
                  :font-family sans-stack
                  :font-weight (str (:semibold typography/weights))
                  :color (:text-tertiary colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:nano typography/type-scale)
                  :letter-spacing (:label-wide typography/letter-spacing)
                  :display "flex"
                  :align-items "center"}
   :tag-row      {:display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :padding "8px 12px 10px 12px"
                  :margin-bottom "6px"
                  :border-bottom (str "1px solid " (:border-subtle colors/tokens))}
   ;; rf2-7ncf9 — faceted tag-filter: one labelled chip row per axis.
   :axis-row     {:display "flex"
                  :flex-direction "column"
                  :gap "3px"}
   :axis-label   {:font-size (:nano typography/type-scale)
                  :color (:text-tertiary colors/tokens)
                  :letter-spacing "0.5px"
                  :text-transform "uppercase"
                  :font-weight "bold"}
   :axis-chips   {:display "flex"
                  :flex-wrap "wrap"
                  :gap "4px"}
   :tag          {:padding "2px 6px"
                  :background (:bg-3 colors/tokens)
                  :color (:text-primary colors/tokens)
                  :border-radius "10px"
                  :cursor "pointer"
                  :font-size (:micro typography/type-scale)
                  :user-select "none"
                  :transition (:chip motion/transitions)}
   :tag-active   {:background (:accent-amber colors/tokens)
                  :color (:text-on-accent colors/tokens)}
   ;; rf2-p0wur — top-level story rows + a generous inter-block gap so
   ;; the sidebar tree breathes. Story rows lead with the diamond
   ;; glyph (`re-frame.story.theme.glyphs/story-glyph`) — the sidebar
   ;; component renders the glyph inline; the glyph itself wears the
   ;; amber accent so each story's parent row reads as a labelled
   ;; chapter heading.
   :story-block  {:margin-bottom "12px"
                  :padding-bottom "2px"}
   :story-row    {:padding "6px 12px 6px 10px"
                  :font-family sans-stack
                  :color (:text-primary colors/tokens)
                  :font-weight (str (:semibold typography/weights))
                  :font-size (:body-tight typography/type-scale)
                  :letter-spacing "0.01em"
                  ;; rf2-8j7wg — story-row is now click-activated (opens
                  ;; the rollup docs page). The cursor flips from
                  ;; `default` to `pointer` so the affordance is obvious.
                  :cursor "pointer"
                  :display "flex"
                  :align-items "center"
                  :gap "8px"
                  :border-left (str "2px solid transparent")}
   :story-row-active {:background (:bg-active colors/tokens)
                      :color (:accent-amber colors/tokens)
                      :border-left (str "2px solid " (:accent-amber colors/tokens))}
   :story-glyph  {:flex-shrink "0"
                  :display "inline-flex"
                  :align-items "center"
                  :color (:accent-amber colors/tokens)}
   :variant-row  {:padding "3px 12px 3px 26px"
                  :cursor "pointer"
                  :color (:text-secondary colors/tokens)
                  :font-family mono-stack
                  :display "flex"
                  :align-items "center"
                  :gap "8px"
                  :border-left (str "2px solid transparent")
                  :transition (:row motion/transitions)}
   :variant-row-active {:background (:bg-active colors/tokens)
                        :color (:accent-amber colors/tokens)
                        :font-weight (str (:medium typography/weights))
                        :border-left (str "2px solid " (:accent-amber colors/tokens))}
   :variant-glyph {:flex-shrink "0"
                   :display "inline-flex"
                   :align-items "center"
                   :justify-content "center"
                   :width "10px"
                   :height "10px"
                   :opacity 0.55
                   :color (:text-tertiary colors/tokens)}
   :workspace-row {:padding "3px 12px 3px 14px"
                   :cursor "pointer"
                   :color (:text-secondary colors/tokens)
                   :font-family mono-stack
                   :display "flex"
                   :align-items "center"
                   :gap "8px"
                   :border-left (str "2px solid transparent")
                   :transition (:row motion/transitions)}
   :workspace-row-active {:background (:bg-active colors/tokens)
                          :color (:accent-amber colors/tokens)
                          :font-weight (str (:medium typography/weights))
                          :border-left (str "2px solid " (:accent-amber colors/tokens))}
   :workspace-glyph {:flex-shrink "0"
                     :display "inline-flex"
                     :align-items "center"
                     :color (:info colors/tokens)
                     :opacity 0.75}
   :empty        {:color (:text-tertiary colors/tokens)
                  :font-style "italic"
                  :padding "8px 12px"}
   ;; rf2-q0irb — status dot + chrome-level test widget.
   :dot          {:width "8px"
                  :height "8px"
                  :border-radius "50%"
                  :flex-shrink "0"
                  :display "inline-block"}
   :dot-pass     {:background (:success colors/tokens)}
   :dot-fail     {:background (:danger colors/tokens)}
   :dot-running  {:background (:warning colors/tokens)
                  :opacity "0.7"}
   :dot-pending  {:background "transparent"
                  :border "1px solid #5a5a5a"}
   :widget       {:border-top "1px solid #333"
                  :margin-top "auto"
                  :padding "10px 12px"
                  :display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :background (:bg-1 colors/tokens)}
   :widget-h     {:font-weight "bold"
                  :color (:text-secondary colors/tokens)
                  :text-transform "uppercase"
                  :font-size (:micro typography/type-scale)
                  :letter-spacing "0.5px"}
   :widget-counts {:display "flex"
                   :flex-wrap "wrap"
                   :gap "8px"
                   :font-family mono-stack
                   :font-size (:caption typography/type-scale)
                   :color (:text-primary colors/tokens)}
   :widget-pass  {:color (:success colors/tokens)}
   :widget-fail  {:color (:danger colors/tokens)}
   :widget-run   {:color (:warning colors/tokens)}
   :widget-pend  {:color (:text-tertiary colors/tokens)}
   :widget-btn   {:margin-top "4px"
                  :padding "4px 10px"
                  :background (:accent-amber colors/tokens)
                  :color "white"
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family mono-stack
                  :font-size (:caption typography/type-scale)}
   :widget-btn-disabled {:background (:bg-3 colors/tokens)
                         :color (:text-tertiary colors/tokens)
                         :cursor "not-allowed"}
   :widget-empty {:color (:text-tertiary colors/tokens)
                  :font-style "italic"
                  :font-size (:micro typography/type-scale)}
   ;; rf2-z1h0f — watch-mode eye-icon toggle on the chrome widget.
   :watch-row    {:display     "flex"
                  :align-items "center"
                  :gap         "8px"
                  :margin-top  "2px"}
   :watch-btn    {:padding         "2px 8px"
                  :background      "transparent"
                  :color           (:text-tertiary colors/tokens)
                  :border          "1px solid #444"
                  :border-radius   "10px"
                  :cursor          "pointer"
                  :font-family     mono-stack
                  :font-size       (:micro typography/type-scale)
                  :letter-spacing  "0.3px"
                  :display         "inline-flex"
                  :align-items     "center"
                  :gap             "4px"}
   :watch-btn-on {:background (:success-bg colors/tokens)
                  :color      (:success colors/tokens)
                  :border     "1px solid #4ec9b0"}
   ;; rf2-nwiwr — tag-as-badge affordance on variant rows.
   :tag-badges   {:display     "inline-flex"
                  :flex-wrap   "wrap"
                  :gap         "3px"
                  :margin-left "4px"}
   :tag-badge    {:padding       "0 5px"
                  :background    (:bg-3 colors/tokens)
                  :color         (:text-primary colors/tokens)
                  :border-radius "8px"
                  :font-family   mono-stack
                  :font-size     (:nano typography/type-scale)
                  :line-height   "14px"
                  :user-select   "none"
                  :flex-shrink   "0"}
   ;; Per-tag palette — keys on the canonical seven from
   ;; spec/007 §Inclusion tags; unknown tags fall through to
   ;; the neutral :tag-badge above.
   :tag-badge-dev          {:background (:tag-dev-bg colors/tokens) :color (:info colors/tokens)}
   :tag-badge-docs         {:background (:tag-docs-bg colors/tokens) :color (:tag-docs-fg colors/tokens)}
   :tag-badge-test         {:background (:success-bg colors/tokens) :color (:success colors/tokens)}
   :tag-badge-screenshot   {:background (:warning-bg colors/tokens) :color (:warning colors/tokens)}
   :tag-badge-experimental {:background (:tag-experimental-bg colors/tokens) :color (:tag-experimental-fg colors/tokens)}
   :tag-badge-internal     {:background (:tag-internal-bg colors/tokens) :color (:danger colors/tokens)}
   :tag-badge-agent        {:background (:tag-agent-bg colors/tokens) :color (:success colors/tokens)}
   ;; rf2-yngai — search-as-you-type input row + amber-tint highlight.
   :search-row     {:padding "0 12px 8px 12px"
                    :display "flex"
                    :align-items "center"
                    :gap "6px"
                    :border-bottom (str "1px solid " (:border-subtle colors/tokens))
                    :margin-bottom "6px"
                    :position "relative"}
   :search-input   {:width "100%"
                    :box-sizing "border-box"
                    :background (:bg-input colors/tokens)
                    :color (:text-primary colors/tokens)
                    :border (str "1px solid " (:border-subtle colors/tokens))
                    :border-radius "4px"
                    :font-family mono-stack
                    :font-size (:caption typography/type-scale)
                    :padding "5px 24px 5px 8px"
                    :outline "none"
                    :transition (:chip motion/transitions)}
   :search-clear   {:position "absolute"
                    :right "18px"
                    :top "50%"
                    :transform "translateY(-50%)"
                    :background "transparent"
                    :border "none"
                    :color (:text-tertiary colors/tokens)
                    :cursor "pointer"
                    :padding "0 4px"
                    :font-family mono-stack
                    :font-size (:caption typography/type-scale)
                    :line-height "1"}
   :search-hit     {:background (:accent-amber-soft colors/tokens)
                    :color (:accent-amber colors/tokens)
                    :border-radius "2px"
                    :padding "0 1px"}})
