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

(rf/reg-event :counter/init
  (fn [{:keys [db]} _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

(defnc root []
  (let [n        (helix-adapter/use-subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
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
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — `:rf/default` is this testbed's app frame, registered
  ;; explicitly here (init! installs only the adapter). The boot dispatch
  ;; runs under the frame scope and the render is wrapped in the Helix
  ;; `frame-provider` so the `use-subscribe` / `frame-handle` reads inside
  ;; `root` resolve to it.
  (rf/init! helix-adapter/adapter)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:counter/init]))
  (.render app-root ($ helix-adapter/frame-provider {:frame :rf/default} ($ root))))
