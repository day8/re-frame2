(ns {{namespace}}.views
  "Compiled views (re-frame.ui — EXPERIMENTAL substrate) — the last
   domino. Each `defview` compiles to a native React component over its
   reactive inputs:

     - `(sub [:counter/value])` is the VALUE, not a ref — nothing to
       deref. When the subscription changes, the view re-renders.
     - an event VECTOR is the handler: `{:on-click [:counter/increment]}`
       dispatches through the compiler-wired synchronous door — no
       closure per render, no `#(dispatch …)` ceremony.

   No useState, no useEffect, no ratoms, no lifecycle ceremony;
   subscriptions deliver values, dispatches send events, and the runtime
   re-renders exactly the views whose inputs changed."
  (:require [re-frame.ui :refer [defview sub]]))

(defview counter-buttons []
  [:div
   [:button {:on-click [:counter/increment]} "+1"]
   [:span {:style {:margin "0 1em"}} (sub [:counter/value])]])

(defview counter-app []
  [:div
   [:h1 "{{name}}"]
   [counter-buttons]])
