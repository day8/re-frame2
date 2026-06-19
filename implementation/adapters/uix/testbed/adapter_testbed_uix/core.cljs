(ns adapter-testbed-uix.core
  "Tiny standalone counter app — the UIx adapter's smoke fixture
   (rf2-eceuv).

   Per TESTING.md §Test surface ownership: examples/ are for humans;
   per-adapter smoke lives with the adapter. This testbed proves the
   UIx adapter wires up end-to-end — mount, subscribe (via the
   `use-subscribe` hook), dispatch, re-render — without depending on
   any example.

   Minimal by design. Don't grow it."
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core    :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event :counter/init
  (fn [{:keys [db]} _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

(defui root []
  (let [n        (uix-adapter/use-subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
    ($ :div
       ($ :h1 {:data-testid "rf-adapter-testbed-uix"}
          "UIx adapter testbed")
       ($ :p ($ :span {:data-testid "rf-adapter-counter"} n))
       ($ :button {:data-testid "rf-adapter-inc"
                   :on-click     #(dispatch [:counter/inc])}
          "+1"))))

;; -- Mount ------------------------------------------------------------------

(defonce app-root
  (uix-dom/create-root (js/document.getElementById "app")))

(defn ^:export init []
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — `:rf/default` is this testbed's app frame, registered
  ;; explicitly here (init! installs only the adapter). The boot dispatch
  ;; runs under the frame scope and the render is wrapped in the UIx
  ;; `frame-provider` so the `use-subscribe` / `frame-handle` reads inside
  ;; `root` resolve to it.
  (rf/init! uix-adapter/adapter)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:counter/init]))
  (uix-dom/render-root
    ($ uix-adapter/frame-provider-existing {:frame :rf/default} ($ root))
    app-root))
