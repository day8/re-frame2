(ns {{namespace}}.views
  "Views — the last domino. Each view is a render function over its
   reactive inputs. No useState, no useEffect, no lifecycle ceremony;
   subscriptions deliver values, dispatches send events, and the runtime
   schedules re-renders when inputs change.

   `rf/reg-view` defines the view symbol *and* registers
   it under (keyword *ns* sym), and auto-injects `dispatch` /
   `subscribe` as lexical bindings resolved to the frame in scope at
   render time.

   Note: this file is starter-template render-path code, kept
   intentionally minimal so the dataflow reads at a glance. The inline
   `#(dispatch ...)` handler and the per-render `subscribe` call are
   fine for a single counter button — they cost one closure allocation
   per render and one subscription cache hit. When you scale up to
   list/grid views (rendering N rows × M cells), revisit:
     - hoist event handlers (or use the substrate's `useCallback`
       equivalent) so children don't re-render on parent identity churn;
     - shape subscriptions so each row subscribes to *its* slice, not
       the whole collection — collection-level subscriptions cause every
       row to re-render on every cell change."
  (:require [re-frame.core :as rf]))

(rf/reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/increment])} "+1"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:counter/value])]])

(rf/reg-view counter-app []
  [:div
   [:h1 "{{name}}"]
   [counter-buttons]])
