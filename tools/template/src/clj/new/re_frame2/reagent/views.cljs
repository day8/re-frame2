(ns {{namespace}}.views
  "Views — the last domino. Each view is a render function over its
   reactive inputs. No useState, no useEffect, no lifecycle ceremony;
   subscriptions deliver values, dispatches send events, and the runtime
   schedules re-renders when inputs change.

   `reg-view` (the Reagent macro) defines the view symbol *and* registers
   it under (keyword *ns* sym), and auto-injects `dispatch` /
   `subscribe` as lexical bindings resolved to the frame in scope at
   render time."
  (:require [re-frame.core    :as rf]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/increment])} "+1"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:counter/value])]])

(reg-view counter-app []
  [:div
   [:h1 "{{name}}"]
   [counter-buttons]])
