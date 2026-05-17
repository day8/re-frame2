(ns day8.re-frame2-causa.panels.app-db-diff
  "App-DB Diff panel shell.

  The panel is sections-centric (rf2-gfxmk Phase 1): it renders the
  structural-diff engine's section vector for the selected epoch,
  pinned live slices, reserved runtime keys, or the 'Show me when this
  changed' result for a focused path.

  Prior to rf2-gfxmk the panel rendered one slice-mini-panel per
  diff triple (stacked before/after cljs-devtools trees per slice).
  The sections-per-cluster model replaces that: N path-headed sections,
  each containing a local annotated subtree with in-place gutters,
  smart-expand, and chip-collapsed `:same` siblings. See
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.1 for the design.

  Canonical exemplar of the panel facade pattern documented in
  `tools/causa/spec/Conventions.md` — facade owns the public
  `reg-view`, leaves expose plain fns + `install!`, the facade's
  `install!` chains leaf installs and returns `nil`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.diff.render :as diff-render]
            [day8.re-frame2-causa.panels.app-db-diff-events :as events]
            [day8.re-frame2-causa.panels.app-db-diff-sections
             :as sections]
            [day8.re-frame2-causa.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack]]))

(rf/reg-view Panel
  "The App-DB Diff panel's root view."
  []
  (let [{:keys [target-frame
                history-empty?
                changed-sections
                changed-reserved
                pinned-slices
                focused-path
                focused-hits]}
        @(rf/subscribe [:rf.causa/app-db-diff])]
    [:section {:data-testid "rf-causa-app-db-diff"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "App-db diff"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Structural diff for this epoch, grouped into path-headed sections."]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        focused-path
        (sections/focus-result-panel focused-path focused-hits)

        history-empty?
        [:div
         (sections/empty-state target-frame)
         (sections/pinned-group pinned-slices)
         (sections/reserved-group changed-reserved)]

        :else
        [:div
         (diff-render/render-sections changed-sections "app-db-diff")
         (sections/pinned-group pinned-slices)
         (sections/reserved-group changed-reserved)])]]))

(defn install!
  "Idempotent install for the App-DB Diff panel's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  nil)
