(ns {{namespace}}.views
  "Views (Helix substrate). Helix uses `defnc` rather than Reagent's
   function-style views; the dataflow is identical. Subscriptions deliver
   values via the `use-subscribe` hook, and `dispatch` comes off the
   `use-frame` hook (capture-frame in hook position). There is no
   auto-injection on the Helix adapter — components ask for both hooks
   explicitly. `use-frame` freezes the render-time frame into a value, so
   the closed-over `dispatch` targets that frame even from an async
   callback.

   Note: this file is starter-template render-path code, kept
   intentionally minimal so the dataflow reads at a glance. The inline
   `#(dispatch ...)` handler and the per-render `use-frame` call are fine
   for a single counter button. When you scale up to list/grid views
   (rendering N rows × M cells), revisit:
     - wrap event handlers in `helix.hooks/use-callback` so children
       memoised with `:helix/memo` don't re-render on parent identity
       churn;
     - keep the `use-frame` destructure to a single `let` per component,
       not one per element call;
     - shape subscriptions so each row subscribes to *its* slice, not
       the whole collection — collection-level subscriptions cause every
       row to re-render on every cell change."
  (:require [helix.core             :refer [$ defnc]]
            [helix.dom              :as d]
            [re-frame.adapter.helix :as helix-adapter]))

(defnc counter-buttons []
  (let [value              (helix-adapter/use-subscribe [:counter/value])
        {:keys [dispatch]} (helix-adapter/use-frame)]
    (d/div
      (d/button {:on-click #(dispatch [:counter/increment])} "+1")
      (d/span {:style {:margin "0 1em"}} value))))

(defnc counter-app []
  (d/div
    (d/h1 "{{name}}")
    ($ counter-buttons)))
