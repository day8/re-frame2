(ns counter.core
 "Pair2 fixture counter.

 Deliberately tiny: one event-db (`:counter/inc`, `:counter/dec`,
 `:counter/initialise`), one sub (`:count`), one reg-view
 (`counter-buttons`). Mirrors examples/reagent/counter/core.cljs and
 exists so re-frame2-pair's tests/shim, tests/e2e, and tests/prompts surfaces
 have a stable target.

 Source-coord annotation is forced ON via `rf/configure` so the
 `data--coord` attribute is present on the rendered DOM —
 re-frame2-pair's DOM ↔ source bridge depends on it (§Tool-Pair §Source-mapping,
 Spec 006 §Source-coord annotation)."
 (:require [reagent.dom.client :as rdc]
 [re-frame.core :as rf]
 [re-frame.views]
 [re-frame.adapter.reagent :as reagent-adapter])
 (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs ------------------------------------------------------------

(rf/reg-event-db :counter/initialise
 (fn [_db _event] {:count 5}))

(rf/reg-event-db :counter/inc
 (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
 (fn [db _event] (update db :count dec)))

(rf/reg-sub :count
 (fn [db _query] (:count db)))

;; -- Views --------------------------------------------------------------------
;;
;; reg-view auto-injects frame-bound `dispatch` / `subscribe`. The macro
;; also captures the source line/column, so the rendered DOM root
;; carries `data--coord="counter.core:counter-buttons:<line>:<col>"`
;; when `:annotate-dom?` is on (set in `run`, below).

(reg-view counter-buttons []
 [:div
 [:button {:id "dec" :on-click #(dispatch [:counter/dec])} "-"]
 [:span {:id "value" :style {:margin "0 1em"}} @(subscribe [:count])]
 [:button {:id "inc" :on-click #(dispatch [:counter/inc])} "+"]])

(reg-view counter-app []
 [counter-buttons])

;; -- Mount --------------------------------------------------------------------

(defonce root
 (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
 ;; Force source-coord DOM annotation on so re-frame2-pair's DOM bridge has
 ;; something to find. Spec 006 §Source-coord annotation.
 (rf/configure :source-coord {:annotate-dom? true})
 (rf/init! reagent-adapter/adapter)
 (rf/dispatch-sync [:counter/initialise])
 (rdc/render root [counter-app]))
