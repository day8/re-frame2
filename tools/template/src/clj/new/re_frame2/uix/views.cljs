(ns {{namespace}}.views
  "Views (UIx substrate). UIx uses `defui` rather than Reagent's
   function-style views; the dataflow is identical — subscriptions
   deliver values via the `use-subscribe` hook, dispatches send events
   via `rf/dispatcher`. Per rf2-3yij Decision 3 there is no
   auto-injection — components call these explicitly."
  (:require [uix.core             :refer [$ defui]]
            [re-frame.core        :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

(defui counter-buttons []
  (let [value    (uix-adapter/use-subscribe [:counter/value])
        dispatch (rf/dispatcher)]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/increment])} "+1")
       ($ :span {:style #js {:margin "0 1em"}} value))))

(defui counter-app []
  ($ :div
     ($ :h1 "{{name}}")
     ($ counter-buttons)))
