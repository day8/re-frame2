(ns day8.re-frame2-causa.panels.ai-co-pilot-views
  "Top-level AI Co-Pilot view shells.

  Plain Reagent fns per the canonical facade convention — the panel's
  three public `reg-view` names (`ai-co-pilot-rail`,
  `ai-co-pilot-cue`, `Panel`) live in the facade
  `ai-co-pilot.cljs`; this leaf supplies the implementation bodies."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-chrome :as chrome]
            [day8.re-frame2-causa.panels.ai-co-pilot-conversation :as conversation]
            [day8.re-frame2-causa.panels.ai-co-pilot-input :as input]
            [day8.re-frame2-causa.theme.tokens :refer [tokens sans-stack]]))

(defn ai-co-pilot-rail
  "Open right-rail form: title bar, scrollable conversation, input row."
  []
  (let [conversation (or @(rf/subscribe [:rf.causa/copilot-conversation]) [])]
    [:aside {:data-testid "rf-causa-copilot-rail"
             :style       {:width "320px"
                           :flex-shrink 0
                           :display "flex"
                           :flex-direction "column"
                           :background (:bg-2 tokens)
                           :border-left (str "1px solid " (:border-subtle tokens))
                           :color (:text-primary tokens)
                           :font-family sans-stack
                           :font-size "13px"}}
     [chrome/title-bar]
     [:div {:style {:flex 1 :overflow-y "auto"}}
      [conversation/conversation-view conversation]]
     ;; Plain function call (not `[input/input-row]`) so the leaf's
     ;; `:rf.causa/copilot-input-text` subscribe resolves to `:rf/causa`
     ;; via this view's wrapper. A component-vector here would mount
     ;; input-row as a separate plain Reagent fn — its subscribe would
     ;; silently route to `:rf/default` (Spec 004 §Plain Reagent fns)
     ;; and the slash popover would never appear. The rf2-043uz fix.
     (input/input-row)]))

(defn ai-co-pilot-cue
  "Collapsed `◇` cue glyph. The pulse stops after first use."
  []
  (let [cue-active? (boolean @(rf/subscribe [:rf.causa/copilot-cue-active?]))]
    [chrome/cue-glyph cue-active?]))

(defn ai-co-pilot-view
  "Canvas panel form for the sidebar Co-pilot row."
  []
  (let [conversation (or @(rf/subscribe [:rf.causa/copilot-conversation]) [])]
    [:section {:data-testid "rf-causa-copilot-panel"
               :style       {:height "100%"
                             :display "flex"
                             :flex-direction "column"
                             :background (:bg-2 tokens)
                             :color (:text-primary tokens)
                             :font-family sans-stack
                             :font-size "14px"}}
     [chrome/title-bar]
     [:div {:style {:flex 1 :overflow-y "auto"}}
      [conversation/conversation-view conversation]]
     ;; Plain function call (not `[input/input-row]`) so the leaf's
     ;; `:rf.causa/copilot-input-text` subscribe resolves to `:rf/causa`
     ;; via this view's wrapper. A component-vector here would mount
     ;; input-row as a separate plain Reagent fn — its subscribe would
     ;; silently route to `:rf/default` (Spec 004 §Plain Reagent fns)
     ;; and the slash popover would never appear. The rf2-043uz fix.
     (input/input-row)]))
