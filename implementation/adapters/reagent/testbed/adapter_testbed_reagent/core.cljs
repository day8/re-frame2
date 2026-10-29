(ns adapter-testbed-reagent.core
  "Tiny standalone counter app — the Reagent adapter's smoke fixture.

   Per TESTING.md §Test surface ownership: examples/ are for humans;
   per-adapter smoke lives with the adapter. This testbed proves the
   Reagent adapter wires up end-to-end — mount, subscribe, dispatch,
   re-render — without depending on any example.

   Minimal by design. Don't grow it. Real coverage is the framework's
   CLJS / browser tests and the Xray feature gate."
  (:require [re-frame.core    :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event :counter/init
  (fn [_ _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

;; `reg-view` binds `dispatch` and `subscribe` to the frame the view renders
;; under, so the click handler still routes there when it fires after the
;; render (Spec 002 §What `reg-view` injects).
(rf/reg-view root []
  [:div
   [:h1 {:data-testid "rf-adapter-testbed-reagent"}
    "Reagent adapter testbed"]
   [:p [:span {:data-testid "rf-adapter-counter"} @(subscribe [:counter/value])]]
   [:button {:data-testid "rf-adapter-inc"
             :on-click     #(dispatch [:counter/inc])}
    "+1"]])

;; -- Mount ------------------------------------------------------------------

;; Inert at namespace load; the first `render!` through it creates the React
;; Root.
(defonce app-root (rf.adapter.reagent/client-root))

(defn ^:export init []
  ;; The boot guide's shape: `frame-root` creates `:rf/default` when it
  ;; commits, runs the `:initial-events` seed once, and scopes the frame to
  ;; the subtree.
  (rf/init! rf.adapter.reagent/adapter)
  (rf.adapter.reagent/render! app-root
    [rf/frame-root {:id             :rf/default
                    :initial-events [[:counter/init]]}
     [root]]
    (js/document.getElementById "app")))
