(ns {{namespace}}.views
  "Views (Helix substrate). Helix uses `defnc` rather than Reagent's
   function-style views; the dataflow is identical. Subscriptions deliver
   values via the `use-subscribe` hook, dispatches send events via
   `(:dispatch (rf/capture-frame))`. There is no auto-injection on the
   Helix adapter — components call these explicitly. The frame api captures
   the render-time frame, so the closed-over `dispatch` targets that
   frame even from an async callback.

   Note: this file is starter-template render-path code, kept
   intentionally minimal so the dataflow reads at a glance. The inline
   `#(dispatch ...)` handler and the per-render `(:dispatch (rf/capture-frame))` call
   are fine for a single counter button. When you scale up to list/grid
   views (rendering N rows × M cells), revisit:
     - wrap event handlers in `helix.hooks/use-callback` so children
       memoised with `:helix/memo` don't re-render on parent identity
       churn;
     - hoist `(:dispatch (rf/capture-frame))` to a single `let` per component, not one
       per element call;
     - shape subscriptions so each row subscribes to *its* slice, not
       the whole collection — collection-level subscriptions cause every
       row to re-render on every cell change."
  (:require [helix.core             :refer [$ defnc]]
            [helix.dom              :as d]
            [re-frame.core          :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

(defnc counter-buttons []
  (let [value    (helix-adapter/use-subscribe [:counter/value])
        dispatch (:dispatch (rf/capture-frame))]
    (d/div
      (d/button {:on-click #(dispatch [:counter/increment])} "+1")
      (d/span {:style {:margin "0 1em"}} value))))

(defnc counter-app []
  (d/div
    (d/h1 "{{name}}")
    ($ counter-buttons)))
