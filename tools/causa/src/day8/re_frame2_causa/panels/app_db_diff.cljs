(ns day8.re-frame2-causa.panels.app-db-diff
  "App-DB Diff panel shell.

  The panel is slice-centric: it renders changed slices for the
  selected epoch, pinned live slices, reserved runtime keys, or the
  'Show me when this changed' result for a focused path.

  Canonical exemplar of the panel facade pattern documented in
  `tools/causa/spec/Conventions.md` — facade owns the public
  `reg-view`, leaves expose plain fns + `install!`, the facade's
  `install!` chains leaf installs and returns `nil`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-events :as events]
            [day8.re-frame2-causa.panels.app-db-diff-sections
             :as sections]
            [day8.re-frame2-causa.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(rf/reg-view app-db-diff-view
  "The App-DB Diff panel's root view."
  []
  (let [{:keys [target-frame
                history-empty?
                changed-non-reserved
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
       "Slice-centric. The slices that changed this epoch + pinned "
       "slices + reserved-keys runtime group. Read-only (Lock #3). Frame: "
       [:code {:style {:color (:accent-violet tokens)
                       :font-family mono-stack}}
        (str target-frame)]]]
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
         (sections/changed-slices-stack changed-non-reserved)
         (sections/pinned-group pinned-slices)
         (sections/reserved-group changed-reserved)])]]))

(defn install!
  "Idempotent install for the App-DB Diff panel's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  nil)
