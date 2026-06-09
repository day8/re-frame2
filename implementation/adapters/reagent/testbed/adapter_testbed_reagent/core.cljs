(ns adapter-testbed-reagent.core
  "Tiny standalone counter app — the Reagent adapter's smoke fixture
   (rf2-eceuv).

   Per TESTING.md §Test surface ownership: examples/ are for humans;
   per-adapter smoke lives with the adapter. This testbed proves the
   Reagent adapter wires up end-to-end — mount, subscribe, dispatch,
   re-render — without depending on any example.

   Minimal by design. Don't grow it. Real coverage is the framework's
   CLJS / browser tests and the Xray feature gate."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event-db :counter/init
  (fn [_db _event] {:counter/value 0}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

(defn root []
  (let [n @(rf/subscribe [:counter/value])]
    [:div
     [:h1 {:data-testid "rf-adapter-testbed-reagent"}
      "Reagent adapter testbed"]
     [:p [:span {:data-testid "rf-adapter-counter"} n]]
     [:button {:data-testid "rf-adapter-inc"
               :on-click     #(rf/dispatch [:counter/inc])}
      "+1"]]))

;; -- Mount ------------------------------------------------------------------

(defonce app-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export init []
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — `:rf/default` is this testbed's app frame, registered
  ;; explicitly here (init! installs only the adapter). The boot dispatch
  ;; runs under the frame scope and the render is wrapped in a
  ;; `frame-provider` so in-tree dispatch/subscribe resolve to it.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:counter/init]))
  (rdc/render app-root [rf/frame-provider {:frame :rf/default} [root]]))
