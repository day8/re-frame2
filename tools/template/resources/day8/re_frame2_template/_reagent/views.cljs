(ns {{namespace}}.views
  "Views. `rf/reg-view` defines the view symbol, registers it under
   (keyword *ns* sym), and binds `dispatch` / `subscribe` to the frame in
   scope at render time."
  (:require [re-frame.core :as rf]))

(rf/reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/increment])} "+1"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:counter/value])]])

;; The `:rf/props` schema gives the story's `:heading` a control.
(rf/reg-view ^{:rf/props [:map [:heading {:optional true} :string]]}
  counter-app [{:keys [heading]}]
  [:div
   [:h1 (or heading "{{name}}")]
   [counter-buttons]])
