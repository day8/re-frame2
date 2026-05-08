(ns counter.core
  "A minimal counter against the re-frame2 API.

   Demonstrates: `reg-event-db`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection, and creation of a non-default frame
   `:counter` from inside a view (Form-2 outer fn) wrapped in
   `frame-provider`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views   :as v])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:count 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))

(rf/reg-sub :count
  (fn [db _query] (:count db)))

;; -- Views -------------------------------------------------------------------
;;
;; The `:counter-buttons` view holds the UI. Its body uses the injected
;; `dispatch`/`subscribe` which resolve, at render time, to whichever frame
;; the surrounding React context puts in scope.

;; reg-view (the macro form from re-frame.views-macros) defs a local
;; Var named after the keyword. The body's dispatch / subscribe both
;; resolve through (current-frame) — bound by the enclosing
;; frame-provider via React context.

(reg-view :counter-buttons
  (fn []
    (let [d (rf/dispatcher)
          s (rf/subscriber)]
      [:div
       [:button {:on-click #(d [:counter/dec])} "-"]
       [:span {:style {:margin "0 1em"}} @(s [:count])]
       [:button {:on-click #(d [:counter/inc])} "+"]])))

;; The `:counter-app` view *creates* the `:counter` frame on mount —
;; Form-2 outer fn — and wraps `:counter-buttons` in a frame-provider so
;; the rendered subtree's dispatch / subscribe target `:counter` rather
;; than the default frame. Re-registration of `:counter` is surgical
;; (preserves app-db / sub-cache / router) so this is safe across hot
;; reloads and remounts.

(reg-view :counter-app
  (fn []
    (rf/reg-frame :counter {:on-create [:counter/initialise]})
    (fn []
      [(v/build-frame-provider :counter)
       :counter
       [counter-buttons]])))

;; -- Mount -------------------------------------------------------------------

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rdc/render root [counter-app]))
