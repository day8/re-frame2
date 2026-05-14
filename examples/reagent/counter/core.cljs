(ns counter.core
  "A minimal counter against the re-frame2 API.

   Demonstrates: `reg-event-db`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection.

   NOTE on frame-provider: an earlier shape of this example wrapped
   `:counter-buttons` in `[rf/frame-provider {:frame :counter} ...]`
   so the subtree resolved its current frame to `:counter` rather
   than `:rf/default`. The example still uses the default frame to
   keep the smoke test focused; the frame-provider variant is
   exercised by examples/reagent/login and the cross-spec test suite."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

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
;; The `:counter-buttons` view holds the UI. Per Spec 004 §reg-view, the
;; defn-shape macro auto-injects `dispatch` and `subscribe` as lexical
;; bindings; both resolve at render time to the frame in scope (the
;; default frame here, see the namespace docstring for the
;; frame-provider variant).
;;
;; reg-view auto-defs a Var named after the supplied symbol and
;; registers the view under (keyword *ns* sym).

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view renders `counter-buttons` against the
;; default frame. Initialisation runs in `run` via `dispatch-sync`.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf/init! takes the adapter spec map directly. Each adapter
  ;; ns exports an `adapter` var; consumers require the ns and pass the
  ;; var explicitly. There is no default-adapter registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter-app]))
