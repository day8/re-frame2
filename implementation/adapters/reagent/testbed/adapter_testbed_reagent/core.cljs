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
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs ----------------------------------------------------------

(rf/reg-event :counter/init
  (fn [{:keys [db]} _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- View -------------------------------------------------------------------

;; EP-0002 (rf2-9o48ih): the render-time `subscribe` below resolves its
;; frame from the closest enclosing `frame-provider` via React context —
;; but ONLY a `reg-view`-registered component carries the `:contextType`
;; static-field wiring Reagent needs to read that context (a plain Reagent
;; fn's `(.-context cmp)` is the no-provider sentinel, so its subscribe
;; would resolve nil → `:rf.error/no-frame-context` and the render crashes).
;; So `root` is a `reg-view`, matching every other frame-scoped Reagent
;; view in the codebase (Spec 002 §Reading the frame from React context).
(reg-view root []
  ;; EP-0002 (rf2-9o48ih): capture a frame-bound `dispatch` at RENDER time via
  ;; `(rf/frame-handle)`. The reg-view resolves the surrounding frame-provider's
  ;; frame through React context here; the handle closes over it, so the
  ;; `:on-click` handler — which fires LATER (on user click), outside any render
  ;; or `with-frame` scope — still routes to `:rf/default`. A bare
  ;; `#(rf/dispatch [:counter/inc])` would resolve no frame at click time and
  ;; raise `:rf.error/no-frame-context` (the runtime never synthesises a frame
  ;; from absence). Mirrors the UIx / Helix testbeds' `frame-handle` pattern.
  (let [n        @(rf/subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
    [:div
     [:h1 {:data-testid "rf-adapter-testbed-reagent"}
      "Reagent adapter testbed"]
     [:p [:span {:data-testid "rf-adapter-counter"} n]]
     [:button {:data-testid "rf-adapter-inc"
               :on-click     #(dispatch [:counter/inc])}
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
  (rdc/render app-root [rf/frame-provider-existing {:frame :rf/default} [root]]))
