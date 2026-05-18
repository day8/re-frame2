(ns causa-rhs-smoke.views
  "Minimal counter view for the causa-rhs-smoke testbed (rf2-drprn).

  The view exposes a `[data-test=\"inc\"]` button and a `[data-test=
  \"count\"]` span — the same idioms the broader counter testbed
  uses, so the regression-scenario assertions read naturally."
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view reg-sub]]))

(reg-sub :count
  (fn [db _query] (:count db 0)))

(reg-view counter-card []
  (let [n @(rf/subscribe [:count])]
    [:div {:style {:padding "1em" :font-family "system-ui, sans-serif"}}
     [:span {:data-test "count"
             :style {:font-weight 600 :margin-right "1em"}}
      (str n)]
     [:button {:data-test "inc"
               :on-click  #(rf/dispatch [:counter/inc])
               :style {:padding "4px 10px"}}
      "+"]]))
