(ns {{namespace}}.views
  "Views (Helix substrate). Helix uses `defnc` rather than Reagent's
   function-style views; the dataflow is identical. Subscriptions deliver
   values via the `use-subscribe` hook, dispatches send events via
   `rf/dispatcher`. Per rf2-2qit Decision 3 there is no auto-injection
   — components call these explicitly."
  (:require [helix.core             :refer [$ defnc]]
            [helix.dom              :as d]
            [re-frame.core          :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

(defnc counter-buttons []
  (let [value    (helix-adapter/use-subscribe [:counter/value])
        dispatch (rf/dispatcher)]
    (d/div
      (d/button {:on-click #(dispatch [:counter/increment])} "+1")
      (d/span {:style {:margin "0 1em"}} value))))

(defnc counter-app []
  (d/div
    (d/h1 "{{name}}")
    ($ counter-buttons)))
