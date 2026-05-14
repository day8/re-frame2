(ns {{namespace}}.views
  "Views (UIx substrate). UIx uses `defui` rather than Reagent's
   function-style views; the dataflow is identical — subscriptions
   deliver values via the `use-subscribe` hook, dispatches send events
   via `rf/dispatcher`. There is no auto-injection on the UIx adapter
   — components call these explicitly.

   Note: this file is starter-template render-path code, kept
   intentionally minimal so the dataflow reads at a glance. The inline
   `#(dispatch ...)` handler and the per-render `(rf/dispatcher)` call
   are fine for a single counter button. When you scale up to list/grid
   views (rendering N rows × M cells), revisit:
     - wrap event handlers in `uix.core/use-callback` so children
       memoised with `defui` don't re-render on parent identity churn;
     - hoist `(rf/dispatcher)` to a single `let` per component, not one
       per JSX node;
     - shape subscriptions so each row subscribes to *its* slice, not
       the whole collection — collection-level subscriptions cause every
       row to re-render on every cell change."
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
