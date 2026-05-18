(ns adapter-testbed-helix.core
  "Tiny standalone counter app — the Helix adapter's smoke fixture
   (rf2-eceuv).

   Per TESTING.md §Test surface ownership: examples/ are for humans;
   per-adapter smoke lives with the adapter. This testbed proves the
   Helix adapter wires up end-to-end — mount, subscribe (via the
   `use-subscribe` hook), dispatch, re-render — without depending on
   any example.

   Minimal by design. Don't grow it."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core      :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event-db :counter/init
  (fn [_db _event] {:counter/value 0}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

(defnc root []
  (let [n        (helix-adapter/use-subscribe [:counter/value])
        dispatch (rf/dispatcher)]
    (d/div
      (d/h1 {:data-testid "rf-adapter-testbed-helix"}
            "Helix adapter testbed")
      (d/p (d/span {:data-testid "rf-adapter-counter"} n))
      (d/button {:data-testid "rf-adapter-inc"
                 :on-click     #(dispatch [:counter/inc])}
        "+1"))))

;; -- Mount ------------------------------------------------------------------

(defonce app-root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export init []
  (rf/init! helix-adapter/adapter)
  (rf/dispatch-sync [:counter/init])
  (.render app-root ($ root)))
