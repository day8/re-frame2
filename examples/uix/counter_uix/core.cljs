(ns counter-uix.core
  "UIx variant of the counter example (rf2-3yij Decision 7).

   Exercises the same dataflow as examples/reagent/counter but renders it
   through the UIx adapter — the React state model is hooks all the way
   down. Demonstrates:

     - `rf/init!` with the UIx adapter
     - `reg-event-db` / `reg-event-fx` / `reg-sub` (substrate-agnostic)
     - `use-subscribe` hook (rf2-3yij Decision 1; UIx idiomatic)
     - `rf/dispatcher` for click handlers (Decision 3 — components call
       dispatch / use-subscribe directly, no auto-injection)
     - The shared frame-context (Decision 2) — the same React Context
       object the Reagent adapter consumes

   Different folder from examples/reagent/counter so the canonical Reagent
   counter is undisturbed; bundle isolation is verified by the
   per-example shadow-cljs builds and the production-elision grep."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core    :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

;; -- Events / subs (handler registry is app-global) --------------------------

(defonce counter-log (atom []))

(rf/reg-fx :counter/log
  (fn [_ctx args]
    (swap! counter-log conj args)))

(rf/reg-event-fx :counter/initialise
  (fn [_ctx _event]
    {:db {:count 5}
     :fx [[:counter/log :initialised]]}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))

(rf/reg-sub :count
  (fn [db _query] (:count db)))

;; -- Views -------------------------------------------------------------------
;;
;; Per rf2-3yij Decision 4 reg-view (the macro) stays Reagent-only;
;; UIx users write `defui` directly. Per Decision 3 there is no auto
;; injection — the component calls `use-subscribe` and `rf/dispatcher`
;; explicitly.

(defui counter-buttons []
  (let [count    (uix-adapter/use-subscribe [:count])
        dispatch (rf/dispatcher)]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/dec])} "-")
       ($ :span {:style #js {:margin "0 1em"}} count)
       ($ :button {:on-click #(dispatch [:counter/inc])} "+"))))

(defui counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------

(defonce root
  (uix-dom/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf2-84po: re-frame.adapter.uix ns-load auto-registers as default.
  (rf/init!)
  (rf/dispatch-sync [:counter/initialise])
  (uix-dom/render-root ($ counter-app) root))
