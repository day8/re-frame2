(ns {{namespace}}.views
  "Views. `rf/reg-view` defines the view symbol, registers it under
   (keyword *ns* sym), and binds `dispatch` / `subscribe` to the frame in
   scope at render time."
  (:require [re-frame.core :as rf]))

(rf/reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/increment])} "+1"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:counter/value])]])

(rf/reg-view counter-app []
  [:div
   [:h1 "{{name}}"]
   [counter-buttons]])
