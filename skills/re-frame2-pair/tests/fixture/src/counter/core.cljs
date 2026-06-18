(ns counter.core
 "re-frame2-pair fixture counter.

 Deliberately tiny: three `reg-event` handlers (`:counter/inc`, `:counter/dec`,
 `:counter/initialise`), one sub (`:count`), one reg-view
 (`counter-buttons`). Mirrors examples/reagent/counter/core.cljs and
 exists so re-frame2-pair's tests/shim, tests/e2e, and tests/prompts surfaces
 have a stable target.

 Source-coord annotation is forced ON via `rf/configure!` so the
 `data--coord` attribute is present on the rendered DOM —
 re-frame2-pair's DOM ↔ source bridge depends on it (§Tool-Pair §Source-mapping,
 Spec 006 §Source-coord annotation)."
 (:require [reagent.dom.client :as rdc]
 [re-frame.core :as rf]
 [re-frame.views]
 [re-frame.adapter.reagent :as reagent-adapter])
 (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs ------------------------------------------------------------

(rf/reg-event :counter/initialise
 (fn [_cofx _event] {:db {:count 5}}))

(rf/reg-event :counter/inc
 (fn [{:keys [db]} _event] {:db (update db :count inc)}))

(rf/reg-event :counter/dec
 (fn [{:keys [db]} _event] {:db (update db :count dec)}))

;; EP-0017 reproducible-coeffects fixture (rf2-jyxmtq). Declares
;; `:rf.cofx/requires [:rf/time-ms]` so its `handler-meta` surfaces the
;; authored declaration, and folds the recorded wall-clock fact (read FLAT as
;; `time-ms`) into durable state under `:stamped-at`. The MCP conformance
;; harness dispatches this with a scripted `cofx "{:rf/time-ms <n>}"` and
;; asserts the SAME `<n>` lands in `:stamped-at` — proving the supplied
;; coeffect reaches the resulting state across the SDK/nREPL wire.
(rf/reg-event :counter/stamp
 {:rf.cofx/requires [:rf/time-ms]}
 (fn [{:keys [db rf/time-ms]} _event]
  {:db (assoc db :stamped-at time-ms)}))

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
 (rf/configure! {:source-coord {:annotate-dom? true}})
 ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
 ;; absence — `:rf/default` is this fixture's app frame, registered
 ;; explicitly here (init! installs only the adapter). The boot dispatch
 ;; runs under the frame scope and the render is wrapped in a
 ;; `frame-provider` so the reg-view-injected dispatch/subscribe resolve
 ;; to it. re-frame2-pair attaches over nREPL and operates this frame.
 (rf/init! reagent-adapter/adapter)
 (rf/reg-frame :rf/default {})
 (rf/with-frame :rf/default
  (rf/dispatch-sync [:counter/initialise]))
 (rdc/render root [rf/frame-provider {:frame :rf/default} [counter-app]]))
