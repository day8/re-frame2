(ns counter-helix.core
  "Helix variant of the counter example.

   Exercises the same dataflow as examples/reagent/counter and
   examples/uix/counter_uix but renders it through the Helix adapter —
   the React state model is hooks all the way down. Demonstrates:

     - `rf/init!` with the Helix adapter
     - `reg-event-db` / `reg-event-fx` / `reg-sub` (substrate-agnostic)
     - `use-subscribe` hook (Helix idiomatic)
     - `rf/dispatcher` for click handlers (components call
       dispatch / use-subscribe directly, no auto-injection)
     - The shared frame-context — the same React Context
       object the Reagent and UIx adapters consume

   Different folder from examples/reagent/counter and
   examples/uix/counter_uix so all three canonical counters are
   undisturbed; bundle isolation is verified by the per-example
   shadow-cljs builds and the production-elision grep."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core      :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

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
;; `reg-view` (the macro) stays Reagent-only;
;; Helix users write `defnc` directly. There is no auto
;; injection — the component calls `use-subscribe` and `rf/dispatcher`
;; explicitly.

(defnc counter-buttons []
  (let [count    (helix-adapter/use-subscribe [:count])
        dispatch (rf/dispatcher)]
    (d/div
       (d/button {:on-click #(dispatch [:counter/dec])} "-")
       (d/span {:style {:margin "0 1em"}} count)
       (d/button {:on-click #(dispatch [:counter/inc])} "+"))))

(defnc counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------

(defonce root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! helix-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (.render root ($ counter-app)))
