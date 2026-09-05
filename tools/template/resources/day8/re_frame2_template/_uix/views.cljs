(ns {{namespace}}.views
  "Views (UIx). Components are `defui`; subscriptions arrive through the
   adapter's `use-subscribe` hook and `dispatch` comes off `use-frame`,
   which captures the render-time frame so a callback targets it later."
  (:require [uix.core             :refer [$ defui]]
            [re-frame.adapter.uix :as rf.adapter.uix]))

(defui counter-buttons []
  (let [value              (rf.adapter.uix/use-subscribe [:counter/value])
        {:keys [dispatch]} (rf.adapter.uix/use-frame)]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/increment])} "+1")
       ($ :span {:style #js {:margin "0 1em"}} value))))

(defui counter-app []
  ($ :div
     ($ :h1 "{{name}}")
     ($ counter-buttons)))
