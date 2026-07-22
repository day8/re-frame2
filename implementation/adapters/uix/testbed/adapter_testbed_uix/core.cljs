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
        dispatch (:dispatch (rf/capture-frame))]
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
  ;; absence — `:rf/default` is this testbed's app frame. The mount goes
  ;; through the UIx `frame-root` ENSURE boundary (rf2-qgfo4): it creates
  ;; the frame at commit time, runs the `:initial-events` seed once, and
  ;; scopes the frame to the subtree so the `use-subscribe` /
  ;; `capture-frame` reads inside `root` resolve to it. This is the
  ;; documented boot idiom (the template scaffold's exact shape) — the
  ;; real-DOM path the smoke must cover.
  (rf/init! uix-adapter/adapter)
  (uix-dom/render-root
    ($ uix-adapter/frame-root {:id             :rf/default
                               :initial-events [[:counter/init]]}
       ($ root))
    app-root))
