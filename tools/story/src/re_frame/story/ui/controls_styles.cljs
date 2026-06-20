(ns re-frame.story.ui.controls-styles
  "Style map for `re-frame.story.ui.controls`. Pure data; no Reagent
  dependency. A separate leaf so the controls ns stays within the
  250-LoC leaf-size ceiling.

  CLJS-only."
  (:require [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

(def styles
  {:wrap        {:padding "8px"
                 :background (:bg-2 colors/tokens)
                 :color (:text-primary colors/tokens)
                 :font-family mono-stack
                 :font-size (:caption typography/type-scale)
                 :border-top (str "1px solid " (:border-default colors/tokens))}
   :section     {:margin-bottom "12px"}
   :section-h   {:font-weight "bold"
                 :color (:text-secondary colors/tokens)
                 :text-transform "uppercase"
                 :font-size (:micro typography/type-scale)
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
                 :border-left (str "1px solid " (:border-strong colors/tokens))
                 :margin-left "4px"
                 :align-items "center"}
   :group-h     {:color (:info colors/tokens)
                 :font-weight "bold"
                 :padding "2px 0"
                 :cursor "default"}
   :label       {:color (:info colors/tokens)}
   :sublabel    {:color (:text-secondary colors/tokens)
                 :font-size (:micro typography/type-scale)}
   :input       {:background (:bg-canvas colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border (str "1px solid " (:border-default colors/tokens))
                 :padding "2px 6px"
                 :font-family mono-stack
                 :font-size (:caption typography/type-scale)
                 :width "100%"}
   :textarea    {:background (:bg-canvas colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border (str "1px solid " (:border-default colors/tokens))
                 :padding "4px 6px"
                 :font-family mono-stack
                 :font-size (:caption typography/type-scale)
                 :width "100%"
                 :min-height "48px"
                 :resize "vertical"}
   :color-input {:background (:bg-canvas colors/tokens)
                 :border (str "1px solid " (:border-default colors/tokens))
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
                 :font-size (:micro typography/type-scale)
                 :user-select "none"}
   :chip-active {:background (:accent-amber colors/tokens)
                 :color (:text-on-accent colors/tokens)}
   :button      {:padding "4px 8px"
                 :background (:accent-amber colors/tokens)
                 :color (:text-on-accent colors/tokens)
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size (:micro typography/type-scale)
                 :margin-top "8px"}
   :rep-button  {:padding "2px 6px"
                 :background (:bg-3 colors/tokens)
                 :color (:text-primary colors/tokens)
                 :border (str "1px solid " (:border-default colors/tokens))
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size (:micro typography/type-scale)
                 :margin-left "4px"}
   :empty       {:color (:text-tertiary colors/tokens) :font-style "italic"}
   ;; ---- validation, diff-from-saved, summarise-before-expand
   ;;
   ;; Inline schema error rendered under a control whose committed value
   ;; violates the component's Spec 010 schema. The danger-bg ground +
   ;; left rule mirror the schema-validation panel's `:violation` style so
   ;; the two surfaces read as one validation language.
   :error       {:color (:danger colors/tokens)
                 :background (:danger-bg colors/tokens)
                 :border-left (str "3px solid " (:danger colors/tokens))
                 :padding "2px 6px"
                 :margin-top "2px"
                 :font-size (:micro typography/type-scale)}
   ;; Panel-level banner shown when ≥1 committed arg violates the schema —
   ;; render / test proof is gated until the violation clears.
   :banner      {:color (:danger colors/tokens)
                 :background (:danger-bg colors/tokens)
                 :border (str "1px solid " (:danger colors/tokens))
                 :border-radius "3px"
                 :padding "4px 6px"
                 :margin-bottom "6px"
                 :font-size (:micro typography/type-scale)}
   ;; The arg-changed-from-saved dot + per-arg reset affordance. The dot
   ;; is amber (Story's "work in progress" signal); the reset is a tight
   ;; inline glyph button sharing the rep-button ground.
   :diff-row    {:display "inline-flex"
                 :gap "4px"
                 :align-items "center"}
   :diff-dot    {:color (:accent-amber colors/tokens)
                 :font-weight "bold"
                 :title "changed from saved"}
   :reset-arg   {:padding "0 4px"
                 :background (:bg-3 colors/tokens)
                 :color (:text-secondary colors/tokens)
                 :border (str "1px solid " (:border-default colors/tokens))
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size (:micro typography/type-scale)}
   ;; The summarise-before-expand toggle for nested (group / repeater /
   ;; tuple) controls. The disclosure triangle + the one-line summary of
   ;; the collapsed value sit in the group header row (the
   ;; deep-controls perf risk in spec 019 §4).
   :disclosure  {:cursor "pointer"
                 :user-select "none"
                 :color (:info colors/tokens)
                 :font-weight "bold"
                 :background "none"
                 :border "none"
                 :padding "0"
                 :font-family mono-stack
                 :font-size (:caption typography/type-scale)
                 :text-align "left"}
   :summary     {:color (:text-tertiary colors/tokens)
                 :font-style "italic"
                 :margin-left "4px"
                 :font-size (:micro typography/type-scale)}
   ;; Flat-panel row cap (budgets/controls-flat-row-cap).
   ;; A controls panel whose top-level arg count exceeds the cap renders the
   ;; first `cap` rows and a "+N more" expander rather than flooding the
   ;; panel (spec/018 §10 — cap or page; fail by summarizing, not flooding).
   ;; Mirrors the sidebar's `:variant-more` affordance language.
   :more        {:padding "4px 0 2px"
                 :color (:text-tertiary colors/tokens)
                 :font-family mono-stack
                 :font-size (:micro typography/type-scale)
                 :font-style "italic"
                 :background "none"
                 :border "none"
                 :cursor "pointer"
                 :user-select "none"
                 :text-align "left"}})
